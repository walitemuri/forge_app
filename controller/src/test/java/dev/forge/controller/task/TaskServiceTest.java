package dev.forge.controller.task;

import dev.forge.controller.scheduler.TaskScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRegistry taskRegistry;

    @Mock
    private TaskAttemptRegistry taskAttemptRegistry;

    @Mock
    private TaskScheduler taskScheduler;

    private TaskService taskService;


    @BeforeEach
    void setUp() {

        taskService =
                new TaskService(
                        taskRegistry,
                        taskAttemptRegistry,
                        taskScheduler
                );
    }


    @Test
    void pendingTaskIsOfferedToScheduler() {

        ForgeTask task =
                task("pending-task");

        task.markPending();

        when(taskRegistry.get(task.getId()))
                .thenReturn(task);

        when(taskScheduler.selectAndReserveWorker())
                .thenReturn(Optional.empty());


        boolean dispatched =
                taskService.tryDispatchPendingTask(
                        task.getId()
                );


        assertFalse(dispatched);
        assertEquals(
                TaskStatus.PENDING,
                task.getStatus()
        );

        verify(taskScheduler)
                .selectAndReserveWorker();
    }


    @Test
    void blockedTaskIsNotOfferedToScheduler() {

        ForgeTask task =
                task("blocked-task");

        task.markBlocked();

        when(taskRegistry.get(task.getId()))
                .thenReturn(task);


        boolean handled =
                taskService.tryDispatchPendingTask(
                        task.getId()
                );


        assertTrue(handled);
        verifyNoInteractions(taskScheduler);
    }


    @Test
    void blockedTaskCanBeCancelled() {

        ForgeTask task =
                task("blocked-task");

        task.markBlocked();

        when(taskRegistry.get(task.getId()))
                .thenReturn(task);


        ForgeTask cancelled =
                taskService.cancelTask(
                        task.getId()
                );


        assertEquals(
                TaskStatus.CANCELLED,
                cancelled.getStatus()
        );

        assertTrue(
                cancelled.isCancelRequested()
        );

        verify(taskRegistry)
                .save(task);
    }


    private ForgeTask task(
            String taskId) {

        return new ForgeTask(
                taskId,
                "echo",
                List.of("hello"),
                1,
                30,
                null
        );
    }
}
