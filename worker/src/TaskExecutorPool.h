#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "ProcessExecutor.h"
#include "forge.grpc.pb.h"


class TaskExecutorPool {

public:

    using Task =
        forge::v1::TaskAssignment;


    using TaskStartedCallback =
        std::function<void(const Task&)>;


    using TaskCompletedCallback =
        std::function<
            void(
                const Task&,
                const ProcessResult&
            )
        >;


    TaskExecutorPool(
        std::size_t workerCount,
        TaskStartedCallback onTaskStarted,
        TaskCompletedCallback onTaskCompleted
    );


    ~TaskExecutorPool();


    TaskExecutorPool(
        const TaskExecutorPool&
    ) = delete;


    TaskExecutorPool& operator=(
        const TaskExecutorPool&
    ) = delete;


    void submit(
        Task task
    );


    /*
     * Works for both queued and running attempts.
     */
    bool cancel(
        const std::string& attemptId
    );


    std::size_t runningTaskCount() const;


    std::size_t queuedTaskCount() const;


private:

    void workerLoop();


    CancellationFlag getCancellationFlag(
        const std::string& attemptId
    );


    void removeCancellationFlag(
        const std::string& attemptId
    );


    std::vector<std::thread>
        workers_;


    std::queue<Task>
        taskQueue_;


    mutable std::mutex
        queueMutex_;


    std::condition_variable
        condition_;


    bool stopping_ =
        false;


    std::atomic<std::size_t>
        runningTasks_{0};


    /*
     * attemptId -> shared cancellation signal
     *
     * main.cpp may set this while one of the executor
     * threads is inside executeProcess().
     */
    std::unordered_map<
        std::string,
        CancellationFlag
    > cancellationFlags_;


    mutable std::mutex
        cancellationMutex_;


    TaskStartedCallback
        onTaskStarted_;


    TaskCompletedCallback
        onTaskCompleted_;
};