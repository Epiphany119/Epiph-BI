package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import com.yupi.springbootinit.modules.chart.application.ReclaimResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 分析应用服务模块测试：ChartApplicationService 使用 Mock，不启动 Spring 或外部基础设施。 */
class AnalysisTaskApplicationServiceTest {

    private static final String WORKER_ID = "worker-test-01";
    private static final int LEASE_SECONDS = 120;
    private static final String VALID_PLAN_JSON =
            "{\"status\":\"READY\",\"dimensionField\":\"地点\",\"seriesField\":null,"
                    + "\"metricField\":\"数量\",\"aggregation\":\"SUM\",\"chartType\":\"bar\","
                    + "\"chartTitle\":\"各地点数量统计\",\"reason\":\"按地点汇总数量\",\"clarificationQuestion\":\"\"}";

    /** 完整同步成功：规划并创建任务、按 workerId 领取租约、调用 Mock AI 并用同一 workerId 保存 SUCCEEDED 结果。 */
    @Test
    void shouldPersistSuccessfulAnalysisWithConsistentWorkerId() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.createAnalysisTask(any())).thenReturn(1L);
        when(chartService.tryStartAnalysis(1L, WORKER_ID, LEASE_SECONDS)).thenReturn(true);
        when(chartService.getAnalysisContext(1L)).thenReturn(context(1L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.completeAnalysis(eq(1L), eq(WORKER_ID), any(), any())).thenReturn(true);
        AiChatClient aiClient = mockTwoStageAiClient(VALID_PLAN_JSON, "{\"genResult\":\"分析完成\"}");
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        AnalysisTaskResult result = service.executeSynchronously(command());

        assertEquals(1L, result.getChartId());
        assertEquals("分析完成", result.getGenResult());
        verify(chartService).tryStartAnalysis(1L, WORKER_ID, LEASE_SECONDS);
        verify(chartService).completeAnalysis(eq(1L), eq(WORKER_ID), any(), eq("分析完成"));
        verify(chartService, never()).failAnalysis(eq(1L), any(), any());
    }

    /** AI 解读阶段失败：分析模块必须携带当前 workerId 调用失败状态 API，并继续向调用方抛出原异常。 */
    @Test
    void shouldPersistFailedStatusWithWorkerIdWhenAiCallFails() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.tryStartAnalysis(2L, WORKER_ID, LEASE_SECONDS)).thenReturn(true);
        when(chartService.getAnalysisContext(2L)).thenReturn(context(2L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.failAnalysis(eq(2L), eq(WORKER_ID), any())).thenReturn(true);
        AiChatClient aiClient = mock(AiChatClient.class);
        when(aiClient.chat(any())).thenThrow(new IllegalStateException("mock AI failure"));
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        assertThrows(IllegalStateException.class, () -> service.executeExistingTask(2L));

        verify(chartService).failAnalysis(2L, WORKER_ID, "mock AI failure");
        verify(chartService, never()).completeAnalysis(eq(2L), any(), any(), any());
    }

    /** 租约失效 Fencing：当 completeAnalysis 返回 false（已被新节点接管）时必须抛异常且只用旧 workerId 尝试失败落库。 */
    @Test
    void shouldFailWhenLeaseWasReclaimedBeforeComplete() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.tryStartAnalysis(20L, WORKER_ID, LEASE_SECONDS)).thenReturn(true);
        when(chartService.getAnalysisContext(20L)).thenReturn(context(20L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.completeAnalysis(eq(20L), eq(WORKER_ID), any(), any())).thenReturn(false);
        when(chartService.failAnalysis(eq(20L), eq(WORKER_ID), any())).thenReturn(false);
        AnalysisTaskApplicationService service = service(chartService, mockAiClient("{\"genResult\":\"过期结果\"}"));

        assertThrows(IllegalStateException.class, () -> service.executeExistingTask(20L));

        verify(chartService).completeAnalysis(eq(20L), eq(WORKER_ID), any(), eq("过期结果"));
        verify(chartService).failAnalysis(eq(20L), eq(WORKER_ID), any());
    }

    /** 失败重试：先请求 chart-biz 原子校验所属用户并重置重试计数，再复用同一 AI 执行流程。 */
    @Test
    void shouldRetryOwnedFailedTaskThroughChartApplicationService() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.prepareRetry(3L, 100L)).thenReturn(context(3L, 100L, AnalysisTaskApplicationService.WAITING));
        when(chartService.tryStartAnalysis(3L, WORKER_ID, LEASE_SECONDS)).thenReturn(true);
        when(chartService.getAnalysisContext(3L)).thenReturn(context(3L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.completeAnalysis(eq(3L), eq(WORKER_ID), any(), any())).thenReturn(true);
        AnalysisTaskApplicationService service = service(chartService,
                mockAiClient("{\"genResult\":\"重试成功\"}"));

        AnalysisTaskResult result = service.retryFailedTask(3L, 100L);

        assertEquals("重试成功", result.getGenResult());
        verify(chartService).prepareRetry(3L, 100L);
    }

    /** 重复 MQ 消息遇到已成功任务时：领取失败并返回已有结果，不再次调用 AI。 */
    @Test
    void shouldReturnExistingResultWithoutCallingAiForSucceededRedelivery() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.tryStartAnalysis(4L, WORKER_ID, LEASE_SECONDS)).thenReturn(false);
        when(chartService.getAnalysisContext(4L)).thenReturn(
                new AnalysisChartContext(4L, 100L, "图表", "目标", "柱状图", "数据",
                        AnalysisTaskApplicationService.SUCCEEDED, "{}", "已完成"));
        AiChatClient aiClient = mock(AiChatClient.class);
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        AnalysisTaskResult result = service.executeExistingTask(4L);

        assertEquals("已完成", result.getGenResult());
        verifyNoInteractions(aiClient);
    }

    /** 重复 MQ 消息遇到 RUNNING 任务时：不重复取得执行权，也不触发第二次模型调用。 */
    @Test
    void shouldNotCallAiWhenAnotherWorkerAlreadyOwnsRunningTask() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.tryStartAnalysis(5L, WORKER_ID, LEASE_SECONDS)).thenReturn(false);
        when(chartService.getAnalysisContext(5L)).thenReturn(context(5L, 100L, AnalysisTaskApplicationService.RUNNING));
        AiChatClient aiClient = mock(AiChatClient.class);
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        service.executeExistingTask(5L);

        verifyNoInteractions(aiClient);
    }

    /** 异步入口完成规划和本地统计后创建 WAITING 任务，返回新建 chartId。 */
    @Test
    void shouldPlanAndCreateWaitingTask() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.createAnalysisTask(any())).thenReturn(6L);
        AiChatClient aiClient = mockAiClient(VALID_PLAN_JSON);
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        Long chartId = service.createWaitingTask(command());

        assertEquals(6L, chartId);
        verify(chartService).createAnalysisTask(any(CreateAnalysisChartCommand.class));
    }

    /** 超时 RUNNING 回收应直接透传到 chart-biz。 */
    @Test
    void shouldDelegateReclaimExpiredRunningTasks() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        ReclaimResult expected = new ReclaimResult(List.of(11L), List.of(12L));
        when(chartService.reclaimExpiredRunningTasks(20)).thenReturn(expected);
        AnalysisTaskApplicationService service = service(chartService, mock(AiChatClient.class));

        ReclaimResult actual = service.reclaimExpiredRunningTasks(20);

        assertEquals(expected, actual);
        verify(chartService).reclaimExpiredRunningTasks(20);
    }

    private AnalysisTaskApplicationService service(ChartApplicationService chartService, AiChatClient aiClient) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new AnalysisTaskApplicationService(chartService, aiClient, new SpreadsheetDataConverter(),
                new AnalysisPromptBuilder(), new AnalysisResultParser(objectMapper),
                new AnalysisPlanParser(objectMapper), new SpreadsheetAnalysisService(objectMapper),
                LEASE_SECONDS, () -> WORKER_ID);
    }

    private AiChatClient mockAiClient(String content) {
        AiChatClient aiClient = mock(AiChatClient.class);
        AiChatResponse response = new AiChatResponse();
        response.setContent(content);
        when(aiClient.chat(any())).thenReturn(response);
        return aiClient;
    }

    private AiChatClient mockTwoStageAiClient(String planJson, String explainJson) {
        AiChatClient aiClient = mock(AiChatClient.class);
        AiChatResponse planResponse = new AiChatResponse();
        planResponse.setContent(planJson);
        AiChatResponse explainResponse = new AiChatResponse();
        explainResponse.setContent(explainJson);
        when(aiClient.chat(any())).thenReturn(planResponse, explainResponse);
        return aiClient;
    }

    private AnalysisChartContext context(Long chartId, Long userId, String status) {
        return new AnalysisChartContext(chartId, userId, "测试图表", "分析地点分布", "柱状图",
                "地点,数量\n北京,10\n上海,20\n", status, null, null);
    }

    private AnalysisTaskCommand command() {
        MockMultipartFile file = new MockMultipartFile("file", "data.csv", "text/csv",
                "地点,数量\n北京,10\n上海,20\n".getBytes());
        return new AnalysisTaskCommand(file, "测试图表", "分析地点分布", "柱状图", 100L);
    }
}
