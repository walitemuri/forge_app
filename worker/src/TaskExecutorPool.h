#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <functional>
#include <mutex>
#include <queue>
#include <thread>
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


    void submit(Task task);


    std::size_t runningTaskCount() const;


    std::size_t queuedTaskCount() const;


private:

    void workerLoop();


    std::vector<std::thread> workers_;

    std::queue<Task> taskQueue_;


    mutable std::mutex queueMutex_;

    std::condition_variable condition_;


    bool stopping_ = false;


    std::atomic<std::size_t> runningTasks_{0};


    TaskStartedCallback onTaskStarted_;

    TaskCompletedCallback onTaskCompleted_;
};