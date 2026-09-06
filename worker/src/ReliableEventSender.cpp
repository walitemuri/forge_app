#include "ReliableEventSender.h"

#include <algorithm>
#include <iostream>
#include <utility>
#include <vector>


void ReliableEventSender::setStream(
        std::shared_ptr<CommandStream> stream) {

    std::vector<
        std::pair<
            std::string,
            forge::v1::WorkerMessage
        >
    > replay;


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        stream_ =
            std::move(stream);


        replay.reserve(
            order_.size()
        );


        for (const auto& eventId : order_) {

            auto iterator =
                pending_.find(
                    eventId
                );


            if (iterator
                    != pending_.end()) {

                replay.emplace_back(
                    eventId,
                    iterator->second
                );
            }
        }
    }


    if (!replay.empty()) {

        std::cout
            << "[outbox] replaying "
            << replay.size()
            << " unacknowledged event(s)\n";
    }


    for (const auto& event : replay) {

        sendOne(
            event.first
        );
    }
}


void ReliableEventSender::clearStream(
        const std::shared_ptr<CommandStream>& expected) {

    std::lock_guard<std::mutex> lock(
        stateMutex_
    );


    if (stream_ == expected) {

        stream_.reset();
    }
}


void ReliableEventSender::enqueue(
        const std::string& eventId,
        forge::v1::WorkerMessage message) {

    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (pending_.contains(
                eventId)) {

            return;
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
        const std::string& eventId) {

    std::size_t remaining = 0;


    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (pending_.erase(
                eventId) == 0) {

            return;
        }


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
ReliableEventSender::pendingCount() const {

    std::lock_guard<std::mutex> lock(
        stateMutex_
    );


    return pending_.size();
}


void ReliableEventSender::sendOne(
        const std::string& eventId) {

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
                || !stream_) {

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


    /*
     * Make sure this stream is still current after
     * waiting for another writer.
     */
    {
        std::lock_guard<std::mutex> lock(
            stateMutex_
        );


        if (stream_ != stream) {

            return;
        }
    }


    if (!stream->Write(
            message)) {

        std::cerr
            << "[outbox] send failed for "
            << eventId
            << "; retaining for replay\n";
    }
}
