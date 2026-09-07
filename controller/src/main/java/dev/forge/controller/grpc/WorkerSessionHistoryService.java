package dev.forge.controller.grpc;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;


@Component
public class WorkerSessionHistoryService {

    private final RetiredWorkerSessionRepository
            repository;


    public WorkerSessionHistoryService(
            RetiredWorkerSessionRepository repository) {

        this.repository =
                repository;
    }


    @Transactional(readOnly = true)
    public boolean isRetired(
            String workerId,
            String sessionId) {

        if (workerId == null
                || sessionId == null) {

            return false;
        }


        return repository
                .existsByWorkerIdAndSessionId(
                        workerId,
                        sessionId
                );
    }


    @Transactional
    public void retire(
            String workerId,
            String sessionId) {

        if (workerId == null
                || workerId.isBlank()
                || sessionId == null
                || sessionId.isBlank()) {

            return;
        }


        repository.insertIfAbsent(
                UUID.randomUUID().toString(),
                workerId,
                sessionId
        );
    }
}
