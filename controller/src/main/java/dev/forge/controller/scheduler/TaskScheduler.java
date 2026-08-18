package dev.forge.controller.scheduler;

import dev.forge.controller.grpc.WorkerRegistry;
import dev.forge.controller.grpc.WorkerState;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;

@Component
public class TaskScheduler {

    public Optional<WorkerState> selectWorker() {

        return WorkerRegistry
                .getWorkers()
                .values()
                .stream()

                .filter(WorkerState::isOnline)

                .filter(WorkerState::hasCommandStream)

                .min(
                    Comparator
                        .comparingInt(
                            WorkerState::getRunningTasks
                        )
                        .thenComparingDouble(
                            WorkerState::getCpuUsage
                        )
                );
    }
}