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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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


    @Test
    void coldStartTakeoverAndAuthoritativeReconnectStayConsistent()
            throws Exception {

        String workerId =
                "boundary-race-worker-"
                        + UUID.randomUUID();

        String sessionA =
                "session-a";

        String sessionC =
                "session-c";


        /*
         * Simulated PostgreSQL authority.
         *
         * Controller restart begins with:
         *
         *     authority = A
         *     WorkerRegistry = empty
         */
        AtomicReference<String> authority =
                new AtomicReference<>(
                        sessionA
                );

        AtomicReference<String> retiredSession =
                new AtomicReference<>(
                        null
                );

        AtomicInteger authorityReads =
                new AtomicInteger();


        CountDownLatch takeoverEntered =
                new CountDownLatch(1);

        CountDownLatch releaseTakeover =
                new CountDownLatch(1);


        when(
                authorityService
                        .getAuthoritativeSession(
                                eq(workerId)
                        )
        ).thenAnswer(invocation -> {

            authorityReads.incrementAndGet();

            return authority.get();
        });


        when(
                sessionHistoryService
                        .isRetired(
                                eq(workerId),
                                anyString()
                        )
        ).thenAnswer(invocation -> {

            String requestedSession =
                    invocation.getArgument(
                            1
                    );

            String retired =
                    retiredSession.get();


            return retired != null
                    && retired.equals(
                            requestedSession
                    );
        });


        /*
         * We are exactly at / just beyond the cold-start grace
         * boundary, so fresh C is eligible to attempt takeover.
         */
        when(
                coldStartGrace.isActive()
        ).thenReturn(
                false
        );


        /*
         * Simulate WorkerTakeoverService's atomic transaction:
         *
         *     retire A
         *     authority A -> C
         *
         * We deliberately block inside the transaction so A can
         * try reconnecting at precisely the dangerous moment.
         */
        doAnswer(invocation -> {

            String expectedSession =
                    invocation.getArgument(
                            1
                    );

            String newSession =
                    invocation.getArgument(
                            2
                    );


            takeoverEntered
                    .countDown();


            boolean released =
                    releaseTakeover
                            .await(
                                    5,
                                    TimeUnit.SECONDS
                            );


            if (!released) {

                throw new AssertionError(
                        "Timed out waiting to release "
                                + "cold-start takeover"
                );
            }


            if (!authority.compareAndSet(
                    expectedSession,
                    newSession)) {

                throw new WorkerTakeoverConflictException(
                        "Simulated authority CAS failed"
                );
            }


            retiredSession.set(
                    expectedSession
            );


            return null;

        }).when(
                takeoverService
        ).takeover(
                eq(workerId),
                eq(sessionA),
                eq(sessionC)
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

            /*
             * Fresh C reaches the expired grace boundary first.
             */
            Future<?> futureC =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestC,
                                            responseC
                                    )
                    );


            assertTrue(
                    takeoverEntered
                            .await(
                                    2,
                                    TimeUnit.SECONDS
                            ),
                    "Fresh session C never entered takeover"
            );


            /*
             * Authoritative A now reconnects while C is blocked
             * halfway through its registration decision.
             */
            Future<?> futureA =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            requestA,
                                            responseA
                                    )
                    );


            /*
             * A must be blocked on the SAME per-worker
             * registration lock.
             *
             * If it reaches PostgreSQL now, we have recreated
             * the old race.
             */
            Thread.sleep(
                    300
            );


            assertEquals(
                    1,
                    authorityReads.get(),
                    "Authoritative A entered the ownership "
                            + "decision while C's takeover was "
                            + "still in progress"
            );


            /*
             * Allow C's durable takeover to commit.
             */
            releaseTakeover
                    .countDown();


            futureC.get(
                    5,
                    TimeUnit.SECONDS
            );

            futureA.get(
                    5,
                    TimeUnit.SECONDS
            );


            /*
             * C won the durable decision.
             *
             * A must subsequently observe itself as retired and
             * must NOT overwrite the in-memory registry.
             */
            assertEquals(
                    sessionC,
                    authority.get(),
                    "Durable authority did not remain with C"
            );


            WorkerState registered =
                    WorkerRegistry.get(
                            workerId
                    );


            assertNotNull(
                    registered,
                    "WorkerRegistry lost the winning worker"
            );


            assertEquals(
                    sessionC,
                    registered.getSessionId(),
                    "WorkerRegistry disagrees with durable authority"
            );


            assertEquals(
                    sessionA,
                    retiredSession.get(),
                    "Superseded A was not retired"
            );

        }
        finally {

            releaseTakeover
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