package dev.forge.controller.api;

import dev.forge.controller.grpc.WorkerRegistry;
import dev.forge.controller.grpc.WorkerState;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;


@RestController
@RequestMapping("/api/workers")
public class WorkerController {


    @GetMapping
    public ResponseEntity<List<WorkerResponse>>
            getWorkers() {

        List<WorkerResponse> workers =
                WorkerRegistry
                        .getWorkers()
                        .values()
                        .stream()

                        /*
                         * Deterministic ordering makes both
                         * clients and tests easier to reason
                         * about.
                         */
                        .sorted(
                                Comparator.comparing(
                                        WorkerState::getWorkerId
                                )
                        )

                        .map(
                                this::toResponse
                        )

                        .toList();


        return ResponseEntity.ok(
                workers
        );
    }


    private WorkerResponse toResponse(
            WorkerState worker) {

        return new WorkerResponse(
                worker.getWorkerId(),
                worker.getSessionId(),
                worker.getHostname(),
                worker.getOperatingSystem(),
                worker.getCpuCores(),
                worker.getMemoryBytes(),
                worker.getCpuUsage(),
                worker.getMemoryUsed(),
                worker.getRunningTasks(),
                worker.getOutstandingTasks(),
                worker.getTaskCapacity(),
                worker.isOnline(),
                worker.hasCommandStream(),
                worker.getLastHeartbeat()
        );
    }
}