package dev.forge.controller.scheduler;

import dev.forge.controller.grpc.WorkerRegistry;
import dev.forge.controller.grpc.WorkerState;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;


@Component
public class TaskScheduler {

    /*
     * Scheduling and reservation must happen atomically.
     *
     * Multiple HTTP requests can call the scheduler at the
     * same time. Without this lock, several requests could
     * all select the same worker before any of them reserves it.
     */
    public synchronized Optional<WorkerState> selectAndReserveWorker() {

        Optional<WorkerState> selectedWorker =
                WorkerRegistry
                        .getWorkers()
                        .values()
                        .stream()

                        .filter(
                                WorkerState::isOnline
                        )

                        .filter(
                                WorkerState::hasCommandStream
                        )
                        .filter(
                                WorkerState::hasTaskCapacity
                        )

                        .min(
                                Comparator
                                        .comparingInt(
                                                WorkerState::getEffectiveLoad
                                        )
                                        .thenComparingDouble(
                                                WorkerState::getCpuUsage
                                        )
                                        .thenComparing(
                                                WorkerState::getWorkerId
                                        )
                        );


        /*
         * This happens while the scheduler lock is still held.
         *
         * Therefore the next request sees the updated load.
         */
        selectedWorker.ifPresent(
                WorkerState::reserveTask
        );


        return selectedWorker;
    }
}