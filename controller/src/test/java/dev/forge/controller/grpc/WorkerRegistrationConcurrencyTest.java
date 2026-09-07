package dev.forge.controller.grpc;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskRegistry;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;

import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class WorkerRegistrationConcurrencyTest {

    @Mock
    private TaskRegistry taskRegistry;

    @Mock
    private TaskAttemptRegistry taskAttemptRegistry;

    @Mock
    private ExecutionEventService executionEventService;

    @Mock
    private WorkerSessionRecoveryCoordinator
            recoveryCoordinator;

    @Mock
    private WorkerAuthorityService
            authorityService;

    @Mock
    private WorkerTakeoverService
            takeoverService;

    @Mock
    private WorkerSessionHistoryService
            sessionHistoryService;

    @Mock
    private WorkerColdStartGrace
            coldStartGrace;


    @Test
    void serializesRegistrationsForSameWorkerId()
            throws Exception {

        String workerId =
                "concurrency-worker-"
                        + UUID.randomUUID();

        String sessionA =
                "session-a";

        String sessionC =
                "session-c";


        CountDownLatch firstAuthorityReadEntered =
                new CountDownLatch(1);

        CountDownLatch releaseFirstAuthorityRead =
                new CountDownLatch(1);

        AtomicInteger authorityReads =
                new AtomicInteger();


        when(
                authorityService
                        .getAuthoritativeSession(
                                eq(workerId)
                        )
        ).thenAnswer(invocation -> {

            int readNumber =
                    authorityReads.incrementAndGet();


            if (readNumber == 1) {

                firstAuthorityReadEntered
                        .countDown();


                boolean released =
                        releaseFirstAuthorityRead
                                .await(
                                        5,
                                        TimeUnit.SECONDS
                                );


                if (!released) {

                    throw new AssertionError(
                            "Timed out waiting to release "
                                    + "first registration"
                    );
                }
            }


            return sessionA;
        });


        when(
                sessionHistoryService
                        .isRetired(
                                eq(workerId),
                                eq(sessionC)
                        )
        ).thenReturn(
                false
        );


        ForgeControllerService service =
                new ForgeControllerService(
                        taskRegistry,
                        taskAttemptRegistry,
                        executionEventService,
                        recoveryCoordinator,
                        authorityService,
                        takeoverService,
                        sessionHistoryService,
                        coldStartGrace
                );


        RegisterWorkerRequest requestA =
                workerRequest(
                        workerId,
                        sessionA
                );

        RegisterWorkerRequest requestC =
                workerRequest(
                        workerId,
                        sessionC
                );


        @SuppressWarnings("unchecked")
        StreamObserver<RegisterWorkerResponse>
                responseA =
                mock(StreamObserver.class);

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterWorkerResponse>
                responseC =
                mock(StreamObserver.class);


        ExecutorService executor =
                Executors.newFixedThreadPool(
                        2
                );


        try {

            Future<?> futureA =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestA,
                                            responseA
                                    )
                    );


            assertTrue(
                    firstAuthorityReadEntered
                            .await(
                                    2,
                                    TimeUnit.SECONDS
                            ),
                    "First registration never entered "
                            + "authority lookup"
            );


            Future<?> futureC =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestC,
                                            responseC
                                    )
                    );


            /*
             * Correct future behavior:
             *
             * C must not enter the authority decision while A
             * is already registering the same stable worker ID.
             *
             * Current implementation should fail here because
             * both RPC threads can enter concurrently.
             */
            Thread.sleep(
                    300
            );


            assertEquals(
                    1,
                    authorityReads.get(),
                    "Concurrent registration entered the "
                            + "authority decision for the same "
                            + "worker ID"
            );


            releaseFirstAuthorityRead
                    .countDown();


            futureA.get(
                    5,
                    TimeUnit.SECONDS
            );

            futureC.get(
                    5,
                    TimeUnit.SECONDS
            );

        }
        finally {

            releaseFirstAuthorityRead
                    .countDown();

            executor.shutdownNow();
        }
    }


    @Test
    void allowsDifferentWorkerIdsToRegisterConcurrently()
            throws Exception {

        String workerIdA =
                "parallel-worker-a-"
                        + UUID.randomUUID();

        String workerIdB =
                "parallel-worker-b-"
                        + UUID.randomUUID();


        CountDownLatch workerAReadEntered =
                new CountDownLatch(1);

        CountDownLatch releaseWorkerARead =
                new CountDownLatch(1);

        CountDownLatch workerBReadEntered =
                new CountDownLatch(1);


        when(
                authorityService
                        .getAuthoritativeSession(
                                anyString()
                        )
        ).thenAnswer(invocation -> {

            String workerId =
                    invocation.getArgument(0);


            if (workerIdA.equals(
                    workerId)) {

                workerAReadEntered
                        .countDown();


                boolean released =
                        releaseWorkerARead
                                .await(
                                        5,
                                        TimeUnit.SECONDS
                                );


                if (!released) {

                    throw new AssertionError(
                            "Timed out waiting to release "
                                    + "worker A"
                    );
                }
            }


            if (workerIdB.equals(
                    workerId)) {

                workerBReadEntered
                        .countDown();
            }


            return null;
        });


        when(
                authorityService
                        .claimIfUnowned(
                                anyString(),
                                anyString()
                        )
        ).thenReturn(
                true
        );


        ForgeControllerService service =
                new ForgeControllerService(
                        taskRegistry,
                        taskAttemptRegistry,
                        executionEventService,
                        recoveryCoordinator,
                        authorityService,
                        takeoverService,
                        sessionHistoryService,
                        coldStartGrace
                );


        RegisterWorkerRequest requestA =
                workerRequest(
                        workerIdA,
                        "session-a"
                );

        RegisterWorkerRequest requestB =
                workerRequest(
                        workerIdB,
                        "session-b"
                );


        @SuppressWarnings("unchecked")
        StreamObserver<RegisterWorkerResponse>
                responseA =
                mock(StreamObserver.class);

        @SuppressWarnings("unchecked")
        StreamObserver<RegisterWorkerResponse>
                responseB =
                mock(StreamObserver.class);


        ExecutorService executor =
                Executors.newFixedThreadPool(
                        2
                );


        try {

            Future<?> futureA =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestA,
                                            responseA
                                    )
                    );


            assertTrue(
                    workerAReadEntered
                            .await(
                                    2,
                                    TimeUnit.SECONDS
                            ),
                    "Worker A never entered "
                            + "authority lookup"
            );


            Future<?> futureB =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestB,
                                            responseB
                                    )
                    );


            /*
             * B has a DIFFERENT stable worker ID.
             *
             * It must be able to reach its authority lookup
             * even while A is deliberately blocked.
             */
            assertTrue(
                    workerBReadEntered
                            .await(
                                    2,
                                    TimeUnit.SECONDS
                            ),
                    "Registration for a different worker ID "
                            + "was unnecessarily serialized"
            );


            releaseWorkerARead
                    .countDown();


            futureA.get(
                    5,
                    TimeUnit.SECONDS
            );

            futureB.get(
                    5,
                    TimeUnit.SECONDS
            );

        }
        finally {

            releaseWorkerARead
                    .countDown();

            executor.shutdownNow();
        }
    }


    private RegisterWorkerRequest workerRequest(
            String workerId,
            String sessionId) {

        return RegisterWorkerRequest
                .newBuilder()
                .setWorkerId(
                        workerId
                )
                .setSessionId(
                        sessionId
                )
                .setHostname(
                        "test-host"
                )
                .setCpuCores(
                        4
                )
                .setMemoryBytes(
                        1024
                )
                .setOperatingSystem(
                        "Linux"
                )
                .build();
    }
}