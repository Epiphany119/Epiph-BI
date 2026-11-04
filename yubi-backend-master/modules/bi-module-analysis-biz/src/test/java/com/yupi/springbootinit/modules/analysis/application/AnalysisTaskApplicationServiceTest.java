package com.yupi.springbootinit.modules.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

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

    /** 完整同步成功：创建任务、领取执行权、调用 Mock AI 并保存 SUCCEEDED 结果。 */
    @Test
    void shouldPersistSuccessfulAnalysisThroughChartApplicationService() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.createAnalysisTask(any())).thenReturn(1L);
        when(chartService.tryStartAnalysis(1L)).thenReturn(true);
        when(chartService.getAnalysisContext(1L)).thenReturn(context(1L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.completeAnalysis(eq(1L), any(), any())).thenReturn(true);
        AiChatClient aiClient = mockAiClient("{\"genChart\":{},\"genResult\":\"分析完成\"}");
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        AnalysisTaskResult result = service.executeSynchronously(command());

        assertEquals(1L, result.getChartId());
        assertEquals("分析完成", result.getGenResult());
        verify(chartService).completeAnalysis(eq(1L), any(), eq("分析完成"));
        verify(chartService, never()).failAnalysis(eq(1L), any());
    }

    /** AI 调用失败：分析模块必须调用独立失败状态 API，并继续向调用方抛出原异常。 */
    @Test
    void shouldPersistFailedStatusWhenAiCallFails() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.createAnalysisTask(any())).thenReturn(2L);
        when(chartService.tryStartAnalysis(2L)).thenReturn(true);
        when(chartService.getAnalysisContext(2L)).thenReturn(context(2L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.failAnalysis(eq(2L), any())).thenReturn(true);
        AiChatClient aiClient = mock(AiChatClient.class);
        when(aiClient.chat(any())).thenThrow(new IllegalStateException("mock AI failure"));
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        assertThrows(IllegalStateException.class, () -> service.executeSynchronously(command()));

        verify(chartService).failAnalysis(2L, "mock AI failure");
        verify(chartService, never()).completeAnalysis(eq(2L), any(), any());
    }

    /** 失败重试：先请求 chart-biz 原子校验所属用户，再复用同一 AI 执行流程。 */
    @Test
    void shouldRetryOwnedFailedTaskThroughChartApplicationService() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.prepareRetry(3L, 100L)).thenReturn(context(3L, 100L, AnalysisTaskApplicationService.WAITING));
        when(chartService.tryStartAnalysis(3L)).thenReturn(true);
        when(chartService.getAnalysisContext(3L)).thenReturn(context(3L, 100L, AnalysisTaskApplicationService.RUNNING));
        when(chartService.completeAnalysis(eq(3L), any(), any())).thenReturn(true);
        AnalysisTaskApplicationService service = service(chartService,
                mockAiClient("{\"genChart\":{},\"genResult\":\"重试成功\"}"));

        AnalysisTaskResult result = service.retryFailedTask(3L, 100L);

        assertEquals("重试成功", result.getGenResult());
        verify(chartService).prepareRetry(3L, 100L);
    }

    /** 重复 MQ 消息遇到已成功任务时：领取失败并返回已有结果，不再次调用 AI。 */
    @Test
    void shouldReturnExistingResultWithoutCallingAiForSucceededRedelivery() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.tryStartAnalysis(4L)).thenReturn(false);
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
        when(chartService.tryStartAnalysis(5L)).thenReturn(false);
        when(chartService.getAnalysisContext(5L)).thenReturn(context(5L, 100L, AnalysisTaskApplicationService.RUNNING));
        AiChatClient aiClient = mock(AiChatClient.class);
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        service.executeExistingTask(5L);

        verifyNoInteractions(aiClient);
    }

    /** 异步入口只创建 WAITING 任务并传递转换后数据，不提前调用模型。 */
    @Test
    void shouldCreateWaitingTaskWithoutCallingAi() {
        ChartApplicationService chartService = mock(ChartApplicationService.class);
        when(chartService.createAnalysisTask(any())).thenReturn(6L);
        AiChatClient aiClient = mock(AiChatClient.class);
        AnalysisTaskApplicationService service = service(chartService, aiClient);

        Long chartId = service.createWaitingTask(command());

        assertEquals(6L, chartId);
        verify(chartService).createAnalysisTask(any(CreateAnalysisChartCommand.class));
        verifyNoInteractions(aiClient);
    }

    private AnalysisTaskApplicationService service(ChartApplicationService chartService, AiChatClient aiClient) {
        return new AnalysisTaskApplicationService(chartService, aiClient, new SpreadsheetDataConverter(),
                new AnalysisPromptBuilder(), new AnalysisResultParser(new ObjectMapper()));
    }

    private AiChatClient mockAiClient(String content) {
        AiChatClient aiClient = mock(AiChatClient.class);
        AiChatResponse response = new AiChatResponse();
        response.setContent(content);
        when(aiClient.chat(any())).thenReturn(response);
        return aiClient;
    }

    private AnalysisChartContext context(Long chartId, Long userId, String status) {
        return new AnalysisChartContext(chartId, userId, "测试图表", "分析地点分布", "柱状图",
                "地点,数量\n北京,10\n", status, null, null);
    }

    private AnalysisTaskCommand command() {
        MockMultipartFile file = new MockMultipartFile("file", "data.csv", "text/csv",
                "地点,数量\n北京,10\n".getBytes());
        return new AnalysisTaskCommand(file, "测试图表", "分析地点分布", "柱状图", 100L);
    }
}
