package com.yupi.springbootinit.modules.analysis.application;

import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.api.AiChatRequest;
import com.yupi.springbootinit.airuntime.api.AiChatResponse;
import com.yupi.springbootinit.modules.analysis.converter.SpreadsheetDataConverter;
import com.yupi.springbootinit.modules.analysis.prompt.AnalysisPromptBuilder;
import com.yupi.springbootinit.modules.chart.application.AnalysisChartContext;
import com.yupi.springbootinit.modules.chart.application.ChartApplicationService;
import com.yupi.springbootinit.modules.chart.application.CreateAnalysisChartCommand;
import org.springframework.stereotype.Service;

/** 智能分析唯一业务编排入口；Controller 和 MQ Consumer 都应复用它。 */
@Service
public class AnalysisTaskApplicationService {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private final ChartApplicationService chartApplicationService;
    private final AiChatClient aiChatClient;
    private final SpreadsheetDataConverter dataConverter;
    private final AnalysisPromptBuilder promptBuilder;
    private final AnalysisResultParser resultParser;

    public AnalysisTaskApplicationService(ChartApplicationService chartApplicationService, AiChatClient aiChatClient,
                                          SpreadsheetDataConverter dataConverter,
                                          AnalysisPromptBuilder promptBuilder,
                                          AnalysisResultParser resultParser) {
        this.chartApplicationService = chartApplicationService;
        this.aiChatClient = aiChatClient;
        this.dataConverter = dataConverter;
        this.promptBuilder = promptBuilder;
        this.resultParser = resultParser;
    }

    /** 同步执行：先创建持久化任务，再通过统一的状态领取逻辑执行。 */
    public AnalysisTaskResult executeSynchronously(AnalysisTaskCommand command) {
        return executeExistingTask(createWaitingTask(command));
    }

    /** 只创建 WAITING 任务，不调用模型；异步线程池和 MQ 入口使用该方法。 */
    public Long createWaitingTask(AnalysisTaskCommand command) {
        validate(command);
        String csvData = dataConverter.convert(command.getFile());
        return chartApplicationService.createAnalysisTask(new CreateAnalysisChartCommand(
                command.getName(), command.getGoal(), command.getChartType(), csvData, command.getUserId()));
    }

    /**
     * 执行已持久化任务。原子领取失败说明该消息已被处理或仍有执行者，不再调用模型。
     */
    public AnalysisTaskResult executeExistingTask(Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new IllegalArgumentException("分析任务 ID 无效");
        }
        boolean acquired = chartApplicationService.tryStartAnalysis(chartId);
        AnalysisChartContext context = chartApplicationService.getAnalysisContext(chartId);
        if (!acquired) {
            if (SUCCEEDED.equalsIgnoreCase(context.getStatus()) || RUNNING.equalsIgnoreCase(context.getStatus())) {
                return resultFrom(context);
            }
            throw new IllegalStateException("当前任务状态不可执行: " + context.getStatus());
        }
        return callAiAndPersist(context);
    }

    /** 失败重试由图表模块原子校验所属用户和 FAILED 状态，再沿同一执行路径领取。 */
    public AnalysisTaskResult retryFailedTask(Long chartId, Long userId) {
        chartApplicationService.prepareRetry(chartId, userId);
        return executeExistingTask(chartId);
    }

    private AnalysisTaskResult callAiAndPersist(AnalysisChartContext context) {
        Long chartId = context.getChartId();
        try {
            AiChatRequest request = new AiChatRequest();
            request.setSystemPrompt(promptBuilder.buildSystemPrompt());
            request.setUserMessage(promptBuilder.buildUserMessage(
                    context.getGoal(), context.getChartType(), context.getChartData()));
            request.setTraceId(String.valueOf(chartId));

            AiChatResponse response = aiChatClient.chat(request);
            AnalysisResult result = resultParser.parse(response.getContent());
            if (!chartApplicationService.completeAnalysis(chartId, result.getGenChart(), result.getGenResult())) {
                throw new IllegalStateException("分析结果保存失败，任务状态已变化");
            }
            return new AnalysisTaskResult(chartId, result.getGenChart(), result.getGenResult());
        } catch (RuntimeException e) {
            // 独立的短事务将失败状态落库，避免 AI 调用异常被长事务回滚。
            chartApplicationService.failAnalysis(chartId, e.getMessage());
            throw e;
        }
    }

    private AnalysisTaskResult resultFrom(AnalysisChartContext context) {
        return new AnalysisTaskResult(context.getChartId(), context.getGenChart(), context.getGenResult());
    }

    private void validate(AnalysisTaskCommand command) {
        if (command == null || command.getUserId() == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (command.getFile() == null || command.getFile().isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (command.getGoal() == null || command.getGoal().isBlank()) {
            throw new IllegalArgumentException("分析目标不能为空");
        }
    }
}
