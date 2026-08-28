#include "TaskExecutorPool.h"

#include <exception>
#include <string>
#include <utility>


TaskExecutorPool::TaskExecutorPool(
        std::size_t workerCount,
        TaskStartedCallback onTaskStarted,
        TaskCompletedCallback onTaskCompleted)

    : onTaskStarted_(
        std::move(onTaskStarted)
    ),
      onTaskCompleted_(
        std::move(onTaskCompleted)
    ) {

    if (workerCount == 0) {
        workerCount = 1;
    }


    workers_.reserve(workerCount);


    for (
        std::size_t i = 0;
        i < workerCount;
        ++i
    ) {

        workers_.emplace_back(
            &TaskExecutorPool::workerLoop,
            this
        );
    }
}


TaskExecutorPool::~TaskExecutorPool() {

    {
        std::lock_guard<std::mutex> lock(
            queueMutex_
        );

        stopping_ = true;
    }


    condition_.notify_all();


    for (auto& worker : workers_) {

        if (worker.joinable()) {
            worker.join();
        }
    }
}


void TaskExecutorPool::submit(
        Task task) {

    {
        std::lock_guard<std::mutex> lock(
            queueMutex_
        );

        taskQueue_.push(
            std::move(task)
        );
    }


    condition_.notify_one();
}


std::size_t
TaskExecutorPool::runningTaskCount() const {

    return runningTasks_.load();
}


std::size_t
TaskExecutorPool::queuedTaskCount() const {

    std::lock_guard<std::mutex> lock(
        queueMutex_
    );

    return taskQueue_.size();
}


void TaskExecutorPool::workerLoop() {

    while (true) {

        Task task;


        // ================================================
        // Wait for work
        // ================================================

        {
            std::unique_lock<std::mutex> lock(
                queueMutex_
            );


            condition_.wait(
                lock,
                [this]() {

                    return stopping_
                        || !taskQueue_.empty();
                }
            );


            if (
                stopping_
                && taskQueue_.empty()
            ) {

                return;
            }


            task =
                std::move(
                    taskQueue_.front()
                );


            taskQueue_.pop();
        }


        // ================================================
        // Execute task
        // ================================================

        runningTasks_.fetch_add(1);


        try {

            onTaskStarted_(task);


            std::vector<std::string> arguments;

            arguments.reserve(
                static_cast<std::size_t>(
                    task.arguments_size()
                )
            );


            for (
                const auto& argument :
                task.arguments()
            ) {

                arguments.push_back(
                    argument
                );
            }


           ProcessResult result =
            executeProcess(
                task.command(),
                arguments,
                task.timeout_seconds()
            );


            onTaskCompleted_(
                task,
                result
            );
        }
        catch (
            const std::exception& exception
        ) {

            ProcessResult result{
                -1,
                "",
                std::string(
                    "Worker execution error: "
                ) + exception.what(),
                false
            };

            onTaskCompleted_(
                task,
                result
            );
        }
        catch (...) {

            ProcessResult result{
                -1,
                "",
                "Unknown worker execution error",
                false
            };


            onTaskCompleted_(
                task,
                result
            );
        }


        runningTasks_.fetch_sub(1);
    }
}