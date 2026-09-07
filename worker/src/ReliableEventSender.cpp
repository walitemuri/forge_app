#include "ReliableEventSender.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstring>
#include <fstream>
#include <iostream>
#include <system_error>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

#ifdef __APPLE__
#include <sys/fcntl.h>
#endif

namespace {

/*
 * Force buffered filesystem state toward durable storage.
 *
 * Linux/WSL:
 *     fsync()
 *
 * macOS:
 *     F_FULLFSYNC when available, then fsync fallback.
 */
bool syncDescriptor(
        int descriptor,
        const std::string& description,
        bool allowUnsupported)
{
#ifdef __APPLE__

    if (::fcntl(
            descriptor,
            F_FULLFSYNC) == 0)
    {
        return true;
    }


    if (errno != EINVAL
            && errno != ENOTSUP)
    {
        std::cerr
            << "[outbox] F_FULLFSYNC failed for "
            << description
            << ": "
            << std::strerror(errno)
            << "\n";

        return false;
    }

#endif


    while (true)
    {
        if (::fsync(
                descriptor) == 0)
        {
            return true;
        }


        if (errno == EINTR)
        {
            continue;
        }


        /*
         * Some filesystems/platforms do not support
         * fsync on directory descriptors.
         *
         * Regular event files never use this escape
         * hatch; only directory metadata syncing does.
         */
        if (allowUnsupported
                && (
                    errno == EINVAL
                    || errno == ENOTSUP
#ifdef EOPNOTSUPP
                    || errno == EOPNOTSUPP
#endif
                ))
        {
            std::cerr
                << "[outbox] directory fsync unsupported for "
                << description
                << "; continuing with reduced metadata "
                << "durability guarantee\n";

            return true;
        }


        std::cerr
            << "[outbox] fsync failed for "
            << description
            << ": "
            << std::strerror(errno)
            << "\n";

        return false;
    }
}


bool syncFile(
        const std::filesystem::path& path)
{
    const int descriptor =
        ::open(
            path.c_str(),
            O_RDONLY
        );


    if (descriptor < 0)
    {
        std::cerr
            << "[outbox] unable to open for fsync "
            << path.string()
            << ": "
            << std::strerror(errno)
            << "\n";

        return false;
    }


    const bool synced =
        syncDescriptor(
            descriptor,
            path.string(),
            false
        );


    ::close(
        descriptor
    );


    return synced;
}


bool syncDirectory(
        const std::filesystem::path& path)
{
    int flags =
        O_RDONLY;

#ifdef O_DIRECTORY

    flags |=
        O_DIRECTORY;

#endif


    const int descriptor =
        ::open(
            path.c_str(),
            flags
        );


    if (descriptor < 0)
    {
        std::cerr
            << "[outbox] unable to open directory for fsync "
            << path.string()
            << ": "
            << std::strerror(errno)
            << "\n";

        return false;
    }


    const bool synced =
        syncDescriptor(
            descriptor,
            path.string(),
            true
        );


    ::close(
        descriptor
    );


    return synced;
}

} // namespace


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


    persistenceThread_ =
        std::thread(
            &ReliableEventSender::persistenceLoop,
            this
        );
}


ReliableEventSender::~ReliableEventSender()
{
    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );

        stopping_ =
            true;
    }


    persistenceCondition_.notify_all();


    if (persistenceThread_.joinable())
    {
        persistenceThread_.join();
    }
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


        durable_.insert(
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


    /*
     * std::ofstream::flush() only flushes userspace
     * buffering. Reopen the completed temporary file
     * and fsync it before making it visible as an
     * event file.
     */
    if (!syncFile(
            temporaryPath))
    {
        std::error_code cleanupError;

        std::filesystem::remove(
            temporaryPath,
            cleanupError
        );

        return false;
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


            /*
             * The event name exists, but make sure the
             * directory metadata is durable before treating
             * this event as safely persisted.
             */
            return syncDirectory(
                storageDirectory_
            );
        }


        std::cerr
            << "[outbox] failed to persist "
            << eventId
            << ": "
            << error.message()
            << "\n";

        return false;
    }


    /*
     * The file contents are durable, but the rename is
     * directory metadata. Sync the containing directory
     * so the .event name itself survives a host crash.
     */
    if (!syncDirectory(
            storageDirectory_))
    {
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


    if (!error)
    {
        /*
         * Persist removal of the acknowledged event.
         *
         * Failure here is safe: the worst case is that
         * the old event reappears after a host crash and
         * is replayed. Controller idempotency will ACK it
         * again.
         */
        if (!syncDirectory(
                storageDirectory_))
        {
            std::cerr
                << "[outbox] unable to durably record removal of "
                << eventId
                << "\n";
        }

        return;
    }


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


bool ReliableEventSender::tryPersistPending(
        const std::string& eventId)
{
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
                == pending_.end())
        {
            return false;
        }


        if (durable_.contains(
                eventId))
        {
            return true;
        }


        message =
            iterator->second;
    }


    /*
     * Disk I/O happens outside stateMutex_ so ACK handling,
     * stream replacement, and queue inspection are not
     * blocked by a slow filesystem.
     */
    if (!persistEvent(
            eventId,
            message))
    {
        return false;
    }


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        /*
         * The event should still exist because an
         * undurable event is never sent and therefore
         * cannot normally be ACKed.
         */
        if (!pending_.contains(
                eventId))
        {
            return false;
        }


        durable_.insert(
            eventId
        );
    }


    std::cout
        << "[outbox] durable "
        << eventId
        << "\n";


    return true;
}


bool ReliableEventSender::hasUndurablePendingLocked() const
{
    for (const auto& eventId :
            order_)
    {
        if (pending_.contains(
                eventId)
                && !durable_.contains(
                    eventId))
        {
            return true;
        }
    }


    return false;
}


void ReliableEventSender::persistenceLoop()
{
    while (true)
    {
        std::vector<std::string>
            retryIds;


        {
            std::unique_lock<std::mutex> lock(
                stateMutex_
            );


            persistenceCondition_.wait(
                lock,
                [this]
                {
                    return stopping_
                        || hasUndurablePendingLocked();
                }
            );


            if (stopping_)
            {
                return;
            }


            for (const auto& eventId :
                    order_)
            {
                if (pending_.contains(
                        eventId)
                        && !durable_.contains(
                            eventId))
                {
                    retryIds.push_back(
                        eventId
                    );
                }
            }
        }


        bool persistenceFailed =
            false;


        /*
         * Preserve queue order. In particular, this keeps
         * TaskAccepted ahead of TaskResult for one attempt.
         */
        for (const auto& eventId :
                retryIds)
        {
            if (tryPersistPending(
                    eventId))
            {
                sendOne(
                    eventId
                );
            }
            else
            {
                persistenceFailed =
                    true;


                std::cerr
                    << "[outbox] persistence retry failed for "
                    << eventId
                    << "; event remains unsent\n";
            }
        }


        if (persistenceFailed)
        {
            /*
             * Avoid spinning if the disk remains unavailable.
             *
             * A newly queued event may notify us sooner;
             * otherwise retry roughly once per second.
             */
            std::unique_lock<std::mutex> lock(
                stateMutex_
            );


            if (stopping_)
            {
                return;
            }


            persistenceCondition_.wait_for(
                lock,
                std::chrono::seconds(1)
            );
        }
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
         * First retain the event in memory.
         *
         * It is NOT eligible for transmission until
         * durable_ contains its event ID.
         */
        pending_.emplace(
            eventId,
            std::move(message)
        );


        order_.push_back(
            eventId
        );
    }


    /*
     * Fast path: try persistence immediately so normal
     * event latency remains essentially unchanged.
     */
    if (tryPersistPending(
            eventId))
    {
        sendOne(
            eventId
        );

        return;
    }


    std::cerr
        << "[outbox] durability unavailable for "
        << eventId
        << "; retaining event in memory and withholding "
        << "gRPC delivery until persistence succeeds\n";


    persistenceCondition_.notify_one();
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


        durable_.erase(
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
                || !stream_
                || !durable_.contains(
                    eventId))
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
