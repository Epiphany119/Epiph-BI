package com.yupi.springbootinit.modules.analysis.job;

import com.yupi.springbootinit.modules.analysis.application.AnalysisTaskApplicationService;
import com.yupi.springbootinit.modules.chart.application.ReclaimResult;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 恢复定时任务单元测试：验证先回收超时 RUNNING 租约、再补偿 WAITING 任务以及异常隔离。 */
class WaitingAnalysisTaskRecoveryJobTest {

    @Test
    void shouldReclaimExpiredRunningBeforeDispatchingWaitingTasks() {
        AnalysisTaskApplicationService service = mock(AnalysisTaskApplicationService.class);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        when(service.reclaimExpiredRunningTasks(20)).thenReturn(new ReclaimResult(List.of(10L), List.of(11L)));
        when(service.findWaitingTaskIds(20)).thenReturn(List.of(10L, 12L));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        WaitingAnalysisTaskRecoveryJob job = new WaitingAnalysisTaskRecoveryJob(service, executor);
        job.recoverWaitingTasks();

        InOrder order = inOrder(service);
        order.verify(service).reclaimExpiredRunningTasks(20);
        order.verify(service).findWaitingTaskIds(20);
        order.verify(service).executeExistingTask(10L);
        order.verify(service).executeExistingTask(12L);
    }

    @Test
    void shouldContinueScanningWaitingTasksWhenReclaimThrows() {
        AnalysisTaskApplicationService service = mock(AnalysisTaskApplicationService.class);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        when(service.reclaimExpiredRunningTasks(20)).thenThrow(new RuntimeException("db timeout"));
        when(service.findWaitingTaskIds(20)).thenReturn(List.of(21L));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        WaitingAnalysisTaskRecoveryJob job = new WaitingAnalysisTaskRecoveryJob(service, executor);
        job.recoverWaitingTasks();

        verify(service).executeExistingTask(21L);
    }

    @Test
    void shouldStopDispatchingCurrentBatchWhenThreadPoolRejects() {
        AnalysisTaskApplicationService service = mock(AnalysisTaskApplicationService.class);
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        when(service.reclaimExpiredRunningTasks(20)).thenReturn(new ReclaimResult(List.of(), List.of()));
        when(service.findWaitingTaskIds(20)).thenReturn(List.of(31L, 32L, 33L));
        doThrow(new RejectedExecutionException("pool full")).when(executor).execute(any(Runnable.class));

        WaitingAnalysisTaskRecoveryJob job = new WaitingAnalysisTaskRecoveryJob(service, executor);
        job.recoverWaitingTasks();

        verify(executor, times(1)).execute(any(Runnable.class));
    }
}
