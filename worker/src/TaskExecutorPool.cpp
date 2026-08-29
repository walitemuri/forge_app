#include "TaskExecutorPool.h"

#include <exception>
#include <memory>
#include <string>
#include <utility>


TaskExecutorPool::TaskExecutorPool(
        std::size_t workerCount,
        TaskStartedCallback onTaskStarted,
        TaskCompletedCallback onTaskCompleted)

    : onTaskStarted_(
        std::move(
            onTaskStarted
        )
    ),
      onTaskCompleted_(
        std::move(
            onTaskCompleted
        )
    ) {

    if (workerCount == 0) {

        workerCount =
            1;
    }


    workers_.reserve(
        workerCount
    );


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


        stopping_ =
            true;
    }


    condition_.notify_all();


    for (auto& worker :
            workers_) {

        if (worker.joinable()) {

            worker.join();
        }
    }
}


void TaskExecutorPool::submit(
        Task task) {

    auto cancellationFlag =
        std::make_shared<
            std::atomic<bool>
        >(
            false
        );


    /*
     * Register before queueing so an immediate
     * CancelTask can already find this attempt.
     */
    {
        std::lock_guard<std::mutex> lock(
            cancellationMutex_
        );


        cancellationFlags_[
            task.attempt_id()
        ] =
            cancellationFlag;
    }


    {
        std::lock_guard<std::mutex> lock(
            queueMutex_
        );


        taskQueue_.push(
            std::move(
                task
            )
        );
    }


    condition_.notify_one();
}


bool TaskExecutorPool::cancel(
        const std::string& attemptId) {

    std::lock_guard<std::mutex> lock(
        cancellationMutex_
    );


    auto iterator =
        cancellationFlags_.find(
            attemptId
        );


    if (iterator
            == cancellationFlags_.end()) {

        return false;
    }


    iterator
            ->second
            ->store(
                true
            );


    return true;
}


CancellationFlag
TaskExecutorPool::getCancellationFlag(
        const std::string& attemptId) {

    std::lock_guard<std::mutex> lock(
        cancellationMutex_
    );


    auto iterator =
        cancellationFlags_.find(
            attemptId
        );


    if (iterator
            == cancellationFlags_.end()) {

        return nullptr;
    }


    return iterator->second;
}


void TaskExecutorPool::removeCancellationFlag(
        const std::string& attemptId) {

    std::lock_guard<std::mutex> lock(
        cancellationMutex_
    );


    cancellationFlags_.erase(
        attemptId
    );
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


            if (stopping_
                    && taskQueue_.empty()) {

                return;
            }


            task =
                std::move(
                    taskQueue_.front()
                );


            taskQueue_.pop();
        }


        CancellationFlag cancellationFlag =
            getCancellationFlag(
                task.attempt_id()
            );


        /*
         * Cancellation can happen while a task is
         * waiting in the worker's local queue.
         *
         * Do not execute it at all.
         */
        if (cancellationFlag
                && cancellationFlag->load()) {

            ProcessResult result{
                130,
                "",
                "Task cancelled before execution\n",
                false,
                true
            };


            onTaskCompleted_(
                task,
                result
            );


            removeCancellationFlag(
                task.attempt_id()
            );


            continue;
        }


        // ================================================
        // Execute
        // ================================================

        runningTasks_.fetch_add(
            1
        );


        try {

            onTaskStarted_(
                task
            );


            std::vector<std::string>
                arguments;


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
                    task.timeout_seconds(),
                    cancellationFlag
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
                false,
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
                false,
                false
            };


            onTaskCompleted_(
                task,
                result
            );
        }


        runningTasks_.fetch_sub(
            1
        );


        removeCancellationFlag(
            task.attempt_id()
        );
    }
}