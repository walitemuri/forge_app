#pragma once

#include <grpcpp/grpcpp.h>

#include <cstddef>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "forge.grpc.pb.h"


class ReliableEventSender {

public:

    using CommandStream =
        grpc::ClientReaderWriter<
            forge::v1::WorkerMessage,
            forge::v1::ControllerMessage
        >;


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

    void sendOne(
        const std::string& eventId
    );


    mutable std::mutex stateMutex_;
    std::mutex writeMutex_;


    std::shared_ptr<CommandStream>
        stream_;


    std::unordered_map<
        std::string,
        forge::v1::WorkerMessage
    > pending_;


    std::deque<std::string>
        order_;
};
