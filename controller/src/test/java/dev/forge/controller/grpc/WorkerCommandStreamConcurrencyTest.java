package dev.forge.controller.grpc;

import dev.forge.controller.event.ExecutionEventService;
import dev.forge.controller.task.TaskAttemptRegistry;
import dev.forge.controller.task.TaskRegistry;

import dev.forge.proto.ControllerMessage;
import dev.forge.proto.RegisterWorkerRequest;
import dev.forge.proto.RegisterWorkerResponse;
import dev.forge.proto.WorkerHello;
import dev.forge.proto.WorkerMessage;

import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;


class WorkerCommandStreamConcurrencyTest {

    @Test
    void staleHelloCannotAttachAfterSessionTakeover()
            throws Exception {

        String workerId =
                "stream-race-worker-"
                        + UUID.randomUUID();

        String sessionA =
                "session-a";

        String sessionC =
                "session-c";


        TaskRegistry taskRegistry =
                mock(TaskRegistry.class);

        TaskAttemptRegistry taskAttemptRegistry =
                mock(TaskAttemptRegistry.class);

        ExecutionEventService executionEventService =
                mock(ExecutionEventService.class);

        WorkerSessionRecoveryCoordinator recoveryCoordinator =
                mock(WorkerSessionRecoveryCoordinator.class);

        WorkerAuthorityService authorityService =
                mock(WorkerAuthorityService.class);

        WorkerTakeoverService takeoverService =
                mock(WorkerTakeoverService.class);

        WorkerSessionHistoryService historyService =
                mock(WorkerSessionHistoryService.class);

        WorkerColdStartGrace coldStartGrace =
                mock(WorkerColdStartGrace.class);


        /*
         * A is the durable authority, but is currently offline.
         *
         * That makes a new session C eligible to take over A.
         */
        WorkerState workerA =
                spy(
                        new WorkerState(
                                workerId,
                                sessionA,
                                "host-a",
                                4,
                                1024,
                                "Linux"
                        )
                );

        workerA.setOnline(
                false
        );


        WorkerRegistry.register(
                workerA
        );


        when(
                authorityService
                        .getAuthoritativeSession(
                                workerId
                        )
        ).thenReturn(
                sessionA
        );


        when(
                historyService
                        .isRetired(
                                workerId,
                                sessionC
                        )
        ).thenReturn(
                false
        );


        CountDownLatch staleHelloValidated =
                new CountDownLatch(
                        1
                );

        CountDownLatch releaseStaleHello =
                new CountDownLatch(
                        1
                );


        /*
         * Pause A's WorkerHello after hasSession(A) has already
         * determined that A matches the WorkerState, but before
         * ConnectWorker can install A's command stream.
         *
         * Calls for other sessions, such as registerWorker(C),
         * continue normally.
         */
        doAnswer(invocation -> {

            String requestedSession =
                    invocation.getArgument(
                            0
                    );


            boolean matches =
                    (boolean)
                            invocation
                                    .callRealMethod();


            if (sessionA.equals(
                    requestedSession)) {

                staleHelloValidated
                        .countDown();


                boolean released =
                        releaseStaleHello
                                .await(
                                        5,
                                        TimeUnit.SECONDS
                                );


                if (!released) {

                    throw new AssertionError(
                            "Timed out waiting to release "
                                    + "stale WorkerHello"
                    );
                }
            }


            return matches;

        }).when(
                workerA
        ).hasSession(
                anyString()
        );


        ForgeControllerService service =
                new ForgeControllerService(
                        taskRegistry,
                        taskAttemptRegistry,
                        executionEventService,
                        recoveryCoordinator,
                        authorityService,
                        takeoverService,
                        historyService,
                        coldStartGrace
                );


        @SuppressWarnings("unchecked")
        StreamObserver<ControllerMessage>
                staleResponse =
                mock(StreamObserver.class);


        StreamObserver<WorkerMessage>
                staleInbound =
                service.connectWorker(
                        staleResponse
                );


        WorkerMessage helloA =
                WorkerMessage
                        .newBuilder()
                        .setHello(
                                WorkerHello
                                        .newBuilder()
                                        .setWorkerId(
                                                workerId
                                        )
                                        .setSessionId(
                                                sessionA
                                        )
                                        .setHostname(
                                                "host-a"
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
                                        .build()
                        )
                        .build();


        RegisterWorkerRequest registerC =
                RegisterWorkerRequest
                        .newBuilder()
                        .setWorkerId(
                                workerId
                        )
                        .setSessionId(
                                sessionC
                        )
                        .setHostname(
                                "host-c"
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


        @SuppressWarnings("unchecked")
        StreamObserver<RegisterWorkerResponse>
                registerResponse =
                mock(StreamObserver.class);


        ExecutorService executor =
                Executors.newFixedThreadPool(
                        2
                );


        try {

            /*
             * Start stale A's WorkerHello.
             */
            Future<?> helloFuture =
                    executor.submit(
                            () ->
                                    staleInbound.onNext(
                                            helloA
                                    )
                    );


            assertTrue(
                    staleHelloValidated
                            .await(
                                    2,
                                    TimeUnit.SECONDS
                            ),
                    "A never reached command-stream validation"
            );


            /*
             * While A is suspended between validation and stream
             * installation, let C take authority.
             */
            Future<?> takeoverFuture =
                    executor.submit(
                            () ->
                                    service.registerWorker(
                                            registerC,
                                            registerResponse
                                    )
                    );


            /*
             * With the CURRENT implementation this is enough for
             * C to complete because ConnectWorker does not share
             * registerWorker's per-worker lock.
             *
             * After the fix C may instead wait for A.
             */
            Thread.sleep(
                    300
            );


            releaseStaleHello
                    .countDown();


            helloFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            takeoverFuture.get(
                    5,
                    TimeUnit.SECONDS
            );


            WorkerState winner =
                    WorkerRegistry.get(
                            workerId
                    );


            assertNotNull(
                    winner,
                    "Winning WorkerState disappeared"
            );


            assertEquals(
                    sessionC,
                    winner.getSessionId(),
                    "Session C did not retain authority"
            );


            /*
             * Core invariant:
             *
             * Once C owns this worker ID, the detached A
             * WorkerState must not retain a command stream.
             *
             * Current code should FAIL here.
             */
            assertNull(
                    workerA.getCommandStream(),
                    "Stale session A attached a command stream "
                            + "after session C took authority"
            );

        }
        finally {

            releaseStaleHello
                    .countDown();

            executor.shutdownNow();

            WorkerRegistry
                    .getWorkers()
                    .remove(
                            workerId
                    );
        }
    }
}
