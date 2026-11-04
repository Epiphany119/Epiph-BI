package com.yupi.springbootinit.modules.chart.application;

import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.service.ChartService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图表应用服务模块测试：Mock 持久化边界，不依赖 Spring、数据库或外部中间件。 */
class ChartApplicationServiceImplTest {

    /** 分析任务创建时必须写入 WAITING 和服务端传入的 userId。 */
    @Test
    void shouldCreateWaitingChartOwnedByAuthenticatedUser() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.save(any(Chart.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Chart.class).setId(101L);
            return true;
        });
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(chartService, mock(ChartMapper.class));

        Long chartId = service.createAnalysisTask(
                new CreateAnalysisChartCommand("门店分布", "分析门店数量", "柱状图", "地点,数量", 9L));

        assertEquals(101L, chartId);
        verify(chartService).save(org.mockito.ArgumentMatchers.argThat(chart ->
                "WAITING".equals(chart.getStatus()) && Long.valueOf(9L).equals(chart.getUserId())
                        && "地点,数量".equals(chart.getChartData())));
    }

    /** 状态领取 SQL 的受影响行数决定是否取得执行权，零行更新代表重复或竞争消费。 */
    @Test
    void shouldUseConditionalUpdateResultForExecutionClaim() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.tryStartAnalysis(102L)).thenReturn(1);
        when(chartMapper.tryStartAnalysis(103L)).thenReturn(0);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);

        assertTrue(service.tryStartAnalysis(102L));
        org.junit.jupiter.api.Assertions.assertFalse(service.tryStartAnalysis(103L));
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

    /** 写入执行失败说明前必须脱敏 Authorization/Bearer 凭据并限制内容长度。 */
    @Test
    void shouldSanitizeCredentialsBeforePersistingFailureMessage() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.failAnalysis(106L, "Authorization=[REDACTED]")).thenReturn(1);
        ChartApplicationServiceImpl service = new ChartApplicationServiceImpl(mock(ChartService.class), chartMapper);

        assertTrue(service.failAnalysis(106L, "Authorization: Bearer top-secret-token"));

        verify(chartMapper).failAnalysis(106L, "Authorization=[REDACTED]");
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
}
