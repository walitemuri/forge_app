#pragma once

#include <grpcpp/grpcpp.h>

#include <cstddef>
#include <condition_variable>
#include <deque>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>

#include "forge.grpc.pb.h"


class ReliableEventSender {

public:

    using CommandStream =
        grpc::ClientReaderWriter<
            forge::v1::WorkerMessage,
            forge::v1::ControllerMessage
        >;


    explicit ReliableEventSender(
        std::filesystem::path storageDirectory
    );

    ~ReliableEventSender();


    void setStream(
        std::shared_ptr<CommandStream> stream
    );


    void clearStream(
        const std::shared_ptr<CommandStream>& expected
    );


    void enqueue(
        const std::string& eventId,
        forge::v1::WorkerMessage message
    );


    void acknowledge(
        const std::string& eventId
    );


    std::size_t pendingCount() const;


private:

    void loadPersistedEvents();


    bool persistEvent(
        const std::string& eventId,
        const forge::v1::WorkerMessage& message
    );


    void removePersistedEvent(
        const std::string& eventId
    );


    std::filesystem::path eventPath(
        const std::string& eventId
    ) const;


    static std::string safeFileName(
        const std::string& eventId
    );


    static std::string extractEventId(
        const forge::v1::WorkerMessage& message
    );


    bool tryPersistPending(
        const std::string& eventId
    );


    bool hasUndurablePendingLocked() const;


    void persistenceLoop();


    void sendReadyEvents();


    std::filesystem::path
        storageDirectory_;


    mutable std::mutex stateMutex_;

    std::mutex writeMutex_;


    std::shared_ptr<CommandStream>
        stream_;


    std::unordered_map<
        std::string,
        forge::v1::WorkerMessage
    > pending_;


    /*
     * Event IDs whose .event file has passed the
     * durable persistence barrier.
     */
    std::unordered_set<std::string>
        durable_;


    /*
     * Events already written on the currently active
     * gRPC stream.
     *
     * Cleared whenever the stream changes so reconnect
     * naturally replays every pending durable event.
     */
    std::unordered_set<std::string>
        sentOnCurrentStream_;


    std::deque<std::string>
        order_;


    std::condition_variable
        persistenceCondition_;

    std::thread
        persistenceThread_;

    bool stopping_ =
        false;
};
