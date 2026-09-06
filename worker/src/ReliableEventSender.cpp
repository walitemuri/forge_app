#include "ReliableEventSender.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iostream>
#include <system_error>
#include <utility>
#include <vector>


ReliableEventSender::ReliableEventSender(
        std::filesystem::path storageDirectory)
    :
        storageDirectory_(
            std::move(storageDirectory)
        )
{
    std::error_code error;

    std::filesystem::create_directories(
        storageDirectory_,
        error
    );


    if (error)
    {
        throw std::runtime_error(
            "Unable to create worker outbox directory "
            + storageDirectory_.string()
            + ": "
            + error.message()
        );
    }


    loadPersistedEvents();


    std::cout
        << "[outbox] storage="
        << storageDirectory_.string()
        << " pending="
        << pendingCount()
        << "\n";
}


void ReliableEventSender::loadPersistedEvents()
{
    std::vector<std::filesystem::path>
        files;


    std::error_code error;


    for (
        std::filesystem::directory_iterator iterator(
            storageDirectory_,
            error
        );

        !error
            && iterator
                != std::filesystem::
                    directory_iterator();

        iterator.increment(error)
    )
    {
        if (!iterator->is_regular_file())
        {
            continue;
        }


        const auto& path =
            iterator->path();


        if (path.extension()
                == ".event")
        {
            files.push_back(
                path
            );
        }
    }


    if (error)
    {
        std::cerr
            << "[outbox] failed to scan "
            << storageDirectory_.string()
            << ": "
            << error.message()
            << "\n";

        return;
    }


    /*
     * Deterministic replay order.
     *
     * For one attempt:
     *
     *   <uuid>_accepted.event
     *   <uuid>_result.event
     *
     * so TaskAccepted naturally loads before TaskResult.
     */
    std::sort(
        files.begin(),
        files.end()
    );


    for (const auto& path : files)
    {
        std::ifstream input(
            path,
            std::ios::binary
        );


        if (!input)
        {
            std::cerr
                << "[outbox] unable to open "
                << path.string()
                << "\n";

            continue;
        }


        forge::v1::WorkerMessage
            message;


        if (!message.ParseFromIstream(
                &input))
        {
            std::cerr
                << "[outbox] corrupt event file "
                << path.string()
                << "\n";

            continue;
        }


        const std::string eventId =
            extractEventId(
                message
            );


        if (eventId.empty())
        {
            std::cerr
                << "[outbox] persisted message "
                << "has no reliable event id: "
                << path.string()
                << "\n";

            continue;
        }


        if (pending_.contains(
                eventId))
        {
            continue;
        }


        pending_.emplace(
            eventId,
            std::move(message)
        );


        order_.push_back(
            eventId
        );
    }


    if (!order_.empty())
    {
        std::cout
            << "[outbox] recovered "
            << order_.size()
            << " event(s) from disk\n";
    }
}


bool ReliableEventSender::persistEvent(
        const std::string& eventId,
        const forge::v1::WorkerMessage& message)
{
    const auto finalPath =
        eventPath(
            eventId
        );


    const auto temporaryPath =
        finalPath.string()
        + ".tmp";


    {
        std::ofstream output(
            temporaryPath,
            std::ios::binary
            | std::ios::trunc
        );


        if (!output)
        {
            std::cerr
                << "[outbox] unable to create "
                << temporaryPath
                << "\n";

            return false;
        }


        if (!message.SerializeToOstream(
                &output))
        {
            std::cerr
                << "[outbox] unable to serialize "
                << eventId
                << "\n";

            return false;
        }


        output.flush();


        if (!output)
        {
            std::cerr
                << "[outbox] unable to flush "
                << temporaryPath
                << "\n";

            return false;
        }
    }


    std::error_code error;


    /*
     * Rename makes the event visible to future worker
     * processes only after the complete protobuf has
     * been written.
     */
    std::filesystem::rename(
        temporaryPath,
        finalPath,
        error
    );


    if (error)
    {
        /*
         * The final file can exist if an earlier copy
         * of this deterministic event ID was already
         * persisted.
         */
        if (std::filesystem::exists(
                finalPath))
        {
            std::filesystem::remove(
                temporaryPath,
                error
            );

            return true;
        }


        std::cerr
            << "[outbox] failed to persist "
            << eventId
            << ": "
            << error.message()
            << "\n";

        return false;
    }


    return true;
}


void ReliableEventSender::removePersistedEvent(
        const std::string& eventId)
{
    std::error_code error;


    std::filesystem::remove(
        eventPath(
            eventId
        ),
        error
    );


    if (error)
    {
        /*
         * This is safe to tolerate.
         *
         * If the stale file survives until a future
         * process restart, Forge will replay it and
         * the controller's attempt/status guards will
         * ACK it again.
         */
        std::cerr
            << "[outbox] unable to remove "
            << eventId
            << " from disk: "
            << error.message()
            << "\n";
    }
}


std::filesystem::path
ReliableEventSender::eventPath(
        const std::string& eventId) const
{
    return storageDirectory_
        / (
            safeFileName(
                eventId
            )
            + ".event"
        );
}


std::string ReliableEventSender::safeFileName(
        const std::string& eventId)
{
    std::string result;

    result.reserve(
        eventId.size()
    );


    for (unsigned char character :
            eventId)
    {
        if (std::isalnum(
                character)
                || character == '-'
                || character == '_'
                || character == '.')
        {
            result.push_back(
                static_cast<char>(
                    character
                )
            );
        }
        else
        {
            result.push_back(
                '_'
            );
        }
    }


    return result;
}


std::string ReliableEventSender::extractEventId(
        const forge::v1::WorkerMessage& message)
{
    if (message.has_task_accepted())
    {
        return message
            .task_accepted()
            .event_id();
    }


    if (message.has_task_result())
    {
        return message
            .task_result()
            .event_id();
    }


    return {};
}


void ReliableEventSender::setStream(
        std::shared_ptr<CommandStream> stream)
{
    std::vector<std::string>
        replay;


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        stream_ =
            std::move(stream);


        replay.assign(
            order_.begin(),
            order_.end()
        );
    }


    if (!replay.empty())
    {
        std::cout
            << "[outbox] replaying "
            << replay.size()
            << " unacknowledged event(s)\n";
    }


    for (const auto& eventId :
            replay)
    {
        sendOne(
            eventId
        );
    }
}


void ReliableEventSender::clearStream(
        const std::shared_ptr<CommandStream>& expected)
{
    std::lock_guard<std::mutex> lock(
        stateMutex_
    );


    if (stream_ == expected)
    {
        stream_.reset();
    }
}


void ReliableEventSender::enqueue(
        const std::string& eventId,
        forge::v1::WorkerMessage message)
{
    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (pending_.contains(
                eventId))
        {
            return;
        }


        /*
         * Durable-before-visible.
         *
         * Do not make the event eligible for sending
         * until its disk copy exists.
         */
        if (!persistEvent(
                eventId,
                message))
        {
            std::cerr
                << "[outbox] WARNING: "
                << "event is being kept only "
                << "in memory because disk "
                << "persistence failed: "
                << eventId
                << "\n";
        }


        pending_.emplace(
            eventId,
            std::move(message)
        );


        order_.push_back(
            eventId
        );
    }


    sendOne(
        eventId
    );
}


void ReliableEventSender::acknowledge(
        const std::string& eventId)
{
    std::size_t remaining = 0;


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (!pending_.contains(
                eventId))
        {
            return;
        }


        /*
         * Controller has durably processed the event,
         * so the persistent copy can now be removed.
         */
        removePersistedEvent(
            eventId
        );


        pending_.erase(
            eventId
        );


        order_.erase(
            std::remove(
                order_.begin(),
                order_.end(),
                eventId
            ),
            order_.end()
        );


        remaining =
            pending_.size();
    }


    std::cout
        << "[outbox] acknowledged "
        << eventId
        << " pending="
        << remaining
        << "\n";
}


std::size_t
ReliableEventSender::pendingCount() const
{
    std::lock_guard<std::mutex> lock(
        stateMutex_
    );


    return pending_.size();
}


void ReliableEventSender::sendOne(
        const std::string& eventId)
{
    std::shared_ptr<CommandStream>
        stream;

    forge::v1::WorkerMessage
        message;


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        auto iterator =
            pending_.find(
                eventId
            );


        if (iterator
                == pending_.end()
                || !stream_)
        {
            return;
        }


        stream =
            stream_;

        message =
            iterator->second;
    }


    std::lock_guard<std::mutex> writeLock(
        writeMutex_
    );


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (stream_ != stream)
        {
            return;
        }
    }


    if (!stream->Write(
            message))
    {
        std::cerr
            << "[outbox] send failed for "
            << eventId
            << "; retaining for replay\n";
    }
}
