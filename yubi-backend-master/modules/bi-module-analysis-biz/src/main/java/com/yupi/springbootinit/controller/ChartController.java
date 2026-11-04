package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.bizmq.BiMessageProducer;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.manager.RedisLimiterManager;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.modules.analysis.application.AnalysisTaskApplicationService;
import com.yupi.springbootinit.modules.analysis.application.AnalysisTaskCommand;
import com.yupi.springbootinit.modules.analysis.application.AnalysisTaskResult;
import com.yupi.springbootinit.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/** AI 分析 HTTP 入口；图表 CRUD 由 chart-biz 单独负责。 */
@RestController
@RequestMapping("/chart")
@Slf4j
public class ChartController {
    private final UserService userService;
    private final RedisLimiterManager redisLimiterManager;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final BiMessageProducer biMessageProducer;
    private final AnalysisTaskApplicationService analysisTaskApplicationService;

    public ChartController(UserService userService, RedisLimiterManager redisLimiterManager,
                           ThreadPoolExecutor threadPoolExecutor, BiMessageProducer biMessageProducer,
                           AnalysisTaskApplicationService analysisTaskApplicationService) {
        this.userService = userService;
        this.redisLimiterManager = redisLimiterManager;
        this.threadPoolExecutor = threadPoolExecutor;
        this.biMessageProducer = biMessageProducer;
        this.analysisTaskApplicationService = analysisTaskApplicationService;
    }

    /** 同步生成分析结果并返回图表 ID、配置和结论。 */
    @PostMapping("/gen")
    public BaseResponse<BiResponse> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
                                                 GenChartByAiRequest requestBody, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());
        AnalysisTaskResult result = analysisTaskApplicationService.executeSynchronously(command(multipartFile, requestBody, loginUser));
        return ResultUtils.success(toBiResponse(result));
    }

    /** 创建待执行任务并交给线程池；响应仍只返回原有 chartId 字段。 */
    @PostMapping("/gen/async")
    public BaseResponse<BiResponse> genChartByAiAsync(@RequestPart("file") MultipartFile multipartFile,
                                                       GenChartByAiRequest requestBody, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());
        Long chartId = analysisTaskApplicationService.createWaitingTask(command(multipartFile, requestBody, loginUser));
        CompletableFuture.runAsync(() -> analysisTaskApplicationService.executeExistingTask(chartId), threadPoolExecutor)
                .exceptionally(error -> {
                    log.error("异步生成图表失败，chartId={}", chartId, error);
                    return null;
                });
        BiResponse response = new BiResponse();
        response.setChartId(chartId);
        return ResultUtils.success(response);
    }

    /** 创建待执行任务并投递 chartId；消费端复用同一分析应用服务。 */
    @PostMapping("/gen/async/mq")
    public BaseResponse<BiResponse> genChartByAiAsyncMq(@RequestPart("file") MultipartFile multipartFile,
                                                         GenChartByAiRequest requestBody, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());
        Long chartId = analysisTaskApplicationService.createWaitingTask(command(multipartFile, requestBody, loginUser));
        biMessageProducer.sendMessage(String.valueOf(chartId));
        BiResponse response = new BiResponse();
        response.setChartId(chartId);
        return ResultUtils.success(response);
    }

    /** 仅允许任务所属用户重试；所有权和 FAILED 状态由 chart-biz 原子校验。 */
    @PostMapping("/retry")
    public BaseResponse<BiResponse> retryChart(long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        AnalysisTaskResult result = analysisTaskApplicationService.retryFailedTask(id, loginUser.getId());
        return ResultUtils.success(toBiResponse(result));
    }

    private AnalysisTaskCommand command(MultipartFile file, GenChartByAiRequest requestBody, User user) {
        if (requestBody == null) {
            throw new IllegalArgumentException("分析请求不能为空");
        }
        return new AnalysisTaskCommand(file, requestBody.getName(), requestBody.getGoal(),
                requestBody.getChartType(), user.getId());
    }

    private BiResponse toBiResponse(AnalysisTaskResult result) {
        BiResponse response = new BiResponse();
        response.setChartId(result.getChartId());
        response.setGenChart(result.getGenChart());
        response.setGenResult(result.getGenResult());
        return response;
    }
}
