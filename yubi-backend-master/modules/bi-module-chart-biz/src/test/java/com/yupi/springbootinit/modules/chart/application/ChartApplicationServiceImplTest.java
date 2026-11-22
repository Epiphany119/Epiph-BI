package com.yupi.springbootinit.modules.chart.application;

import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图表应用服务模块测试：Mock 持久化边界，不依赖 Spring、数据库或外部中间件。 */
class ChartApplicationServiceImplTest {

    /** 分析任务创建时必须写入 WAITING、服务端传入的 userId 以及默认重试配额。 */
    @Test
    void shouldCreateWaitingChartOwnedByAuthenticatedUserWithDefaultRetryQuota() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.save(any(Chart.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Chart.class).setId(101L);
            return true;
        });
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(chartService, mock(ChartMapper.class));

        Long chartId = service.createAnalysisTask(
                new CreateAnalysisChartCommand("门店分布", "分析门店数量", "柱状图", "地点,数量", 9L));

        assertEquals(101L, chartId);
        verify(chartService).save(argThat(chart ->
                "WAITING".equals(chart.getStatus())
                        && Long.valueOf(9L).equals(chart.getUserId())
                        && "地点,数量".equals(chart.getChartData())
                        && Integer.valueOf(0).equals(chart.getAttemptCount())
                        && Integer.valueOf(ChartApplicationServiceImpl.DEFAULT_MAX_ATTEMPTS).equals(chart.getMaxAttempts())));
    }

    /** 状态领取 SQL 的受影响行数决定是否取得执行权，并透传 workerId 和租约时长。 */
    @Test
    void shouldUseConditionalUpdateResultForLeaseClaim() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.tryStartAnalysis(102L, "worker-a", 120)).thenReturn(1);
        when(chartMapper.tryStartAnalysis(103L, "worker-b", 120)).thenReturn(0);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);

        assertTrue(service.tryStartAnalysis(102L, "worker-a", 120));
        assertFalse(service.tryStartAnalysis(103L, "worker-b", 120));
    }

    /** 缺少执行节点标识或非法租约时长时必须立即拒绝，避免产生无 Fencing 的 RUNNING 任务。 */
    @Test
    void shouldRejectBlankWorkerIdOrNonPositiveLeaseSeconds() {
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), mock(ChartMapper.class));

        assertThrows(BusinessException.class, () -> service.tryStartAnalysis(102L, "  ", 120));
        assertThrows(BusinessException.class, () -> service.tryStartAnalysis(102L, "worker-a", 0));
        assertThrows(BusinessException.class, () -> service.completeAnalysis(102L, "", "{}", "ok"));
        assertThrows(BusinessException.class, () -> service.failAnalysis(102L, null, "err"));
    }

    /** 完成任务必须携带 workerId 作为 Fencing Token，旧节点写入被底层返回 0 时应返回 false。 */
    @Test
    void shouldEnforceWorkerIdFencingOnCompleteAnalysis() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.completeAnalysis(102L, "worker-active", "{}", "ok")).thenReturn(1);
        when(chartMapper.completeAnalysis(102L, "worker-stale", "{}", "stale")).thenReturn(0);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);

        assertTrue(service.completeAnalysis(102L, "worker-active", "{}", "ok"));
        assertFalse(service.completeAnalysis(102L, "worker-stale", "{}", "stale"));
    }

    /** 超时 RUNNING 任务回收：未达重试上限回退 WAITING，已达上限原子置为 FAILED。 */
    @Test
    void shouldRequeueUnderLimitAndFailExhaustedExpiredRunningTasks() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        Chart retryable = expiredRunningChart(201L, 1, 3, "worker-1");
        Chart exhausted = expiredRunningChart(202L, 3, 3, "worker-2");
        Chart raced = expiredRunningChart(203L, 2, 3, "worker-3");
        when(chartMapper.findExpiredRunningTasks(20)).thenReturn(List.of(retryable, exhausted, raced));
        when(chartMapper.requeueExpiredRunningTask(201L, "worker-1")).thenReturn(1);
        when(chartMapper.failExpiredRunningTask(202L, "worker-2",
                ChartApplicationServiceImpl.EXPIRED_MAX_ATTEMPTS_MESSAGE)).thenReturn(1);
        when(chartMapper.requeueExpiredRunningTask(203L, "worker-3")).thenReturn(0);

        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);
        ReclaimResult result = service.reclaimExpiredRunningTasks(20);

        assertEquals(List.of(201L), result.getRequeuedChartIds());
        assertEquals(List.of(202L), result.getFailedChartIds());
        assertEquals(1, result.getRequeuedCount());
        assertEquals(1, result.getFailedCount());
    }

    /** 回收批次参数越界必须拒绝。 */
    @Test
    void shouldRejectInvalidReclaimLimit() {
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), mock(ChartMapper.class));

        assertThrows(BusinessException.class, () -> service.reclaimExpiredRunningTasks(0));
        assertThrows(BusinessException.class, () -> service.reclaimExpiredRunningTasks(101));
    }

    /** 重试前必须验证任务归属；非所属用户不能触发任何 FAILED→WAITING 状态更新。 */
    @Test
    void shouldRejectRetryFromAnotherUserBeforeUpdatingState() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(104L)).thenReturn(chart(104L, 12L, "FAILED"));
        ChartMapper chartMapper = mock(ChartMapper.class);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(chartService, chartMapper);

        assertThrows(BusinessException.class, () -> service.prepareRetry(104L, 99L));

        verify(chartMapper, never()).prepareRetry(104L, 99L);
    }

    /** 所属用户重试失败任务后，服务返回 WAITING 上下文并清除旧失败说明。 */
    @Test
    void shouldPrepareRetryAndReturnRefreshedTaskContext() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(105L)).thenReturn(
                chart(105L, 12L, "FAILED"), chart(105L, 12L, "WAITING"));
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.prepareRetry(105L, 12L)).thenReturn(1);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(chartService, chartMapper);

        AnalysisChartContext context = service.prepareRetry(105L, 12L);

        assertEquals("WAITING", context.getStatus());
        assertEquals(12L, context.getUserId());
    }

    /** 写入执行失败说明前必须脱敏 Authorization/Bearer 凭据并透传 workerId。 */
    @Test
    void shouldSanitizeCredentialsBeforePersistingFailureMessage() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.failAnalysis(106L, "worker-1", "Authorization=[REDACTED]")).thenReturn(1);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);

        assertTrue(service.failAnalysis(106L, "worker-1", "Authorization: Bearer top-secret-token"));

        verify(chartMapper).failAnalysis(106L, "worker-1", "Authorization=[REDACTED]");
    }

    private Chart chart(Long id, Long userId, String status) {
        Chart chart = new Chart();
        chart.setId(id);
        chart.setUserId(userId);
        chart.setStatus(status);
        chart.setName("门店分布");
        chart.setGoal("分析门店数量");
        chart.setChartType("柱状图");
        chart.setChartData("地点,数量\n北京,10\n");
        return chart;
    }

    private Chart expiredRunningChart(Long id, int attemptCount, int maxAttempts, String workerId) {
        Chart chart = new Chart();
        chart.setId(id);
        chart.setStatus("RUNNING");
        chart.setAttemptCount(attemptCount);
        chart.setMaxAttempts(maxAttempts);
        chart.setWorkerId(workerId);
        return chart;
    }
}
