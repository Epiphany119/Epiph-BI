package com.yupi.springbootinit.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.DeleteRequest;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.PageRequest;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.dto.chart.ChartAddRequest;
import com.yupi.springbootinit.model.dto.chart.ChartEditRequest;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.dto.chart.ChartUpdateRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Set;

/** 图表基础信息 CRUD 路由；AI 生成、异步执行和重试归 analysis-biz。 */
@RestController
@RequestMapping("/chart")
public class ChartManagementController {
    private static final long MAX_PAGE_SIZE = 20;
    private static final Set<String> SORT_FIELDS = Set.of("id", "name", "goal", "chartType", "status", "userId", "createTime", "updateTime");

    private final ChartService chartService;
    private final ChartMapper chartMapper;
    private final UserService userService;

    public ChartManagementController(ChartService chartService,
                                    ChartMapper chartMapper,
                                    UserService userService) {
        this.chartService = chartService;
        this.chartMapper = chartMapper;
        this.userService = userService;
    }

    /** 手动创建图表；归属用户始终取自服务端登录态。 */
    @PostMapping("/add")
    public BaseResponse<Long> addChart(@RequestBody ChartAddRequest requestBody, HttpServletRequest request) {
        if (requestBody == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        Chart chart = new Chart();
        chart.setName(requestBody.getName());
        chart.setGoal(requestBody.getGoal());
        chart.setChartData(requestBody.getChartData());
        chart.setChartType(requestBody.getChartType());
        chart.setUserId(user.getId());
        if (!chartService.save(chart)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(chart.getId());
    }

    /** 逻辑删除本人或管理员图表，并在数据库更新时再次保护 RUNNING 状态。 */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteChart(@RequestBody DeleteRequest requestBody, HttpServletRequest request) {
        if (requestBody == null || requestBody.getId() == null || requestBody.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = getExistingChart(requestBody.getId());
        User loginUser = userService.getLoginUser(request);
        requireOwnerOrAdmin(chart, loginUser);
        if (isRunning(chart.getStatus()) || chartMapper.deleteIfNotRunning(chart.getId()) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "执行中的任务不能删除，或图表状态已变化");
        }
        return ResultUtils.success(true);
    }

    /** 更新入口仅管理员可用，且服务端只复制基础信息白名单。 */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateChart(@RequestBody ChartUpdateRequest requestBody, HttpServletRequest request) {
        if (requestBody == null || requestBody.getId() == null || requestBody.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        if (!userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        getExistingChart(requestBody.getId());
        Chart update = new Chart();
        update.setId(requestBody.getId());
        update.setName(requestBody.getName());
        update.setGoal(requestBody.getGoal());
        update.setChartType(requestBody.getChartType());
        return ResultUtils.success(chartService.updateById(update));
    }

    /** 详情只对所属用户或管理员开放。 */
    @GetMapping("/get")
    public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
        Chart chart = getExistingChart(id);
        requireOwnerOrAdmin(chart, userService.getLoginUser(request));
        return ResultUtils.success(chart);
    }

    /** 全量图表列表为管理员能力；普通用户必须使用 /my/list/page。 */
    @PostMapping("/list/page")
    public BaseResponse<Page<Chart>> listChartByPage(@RequestBody ChartQueryRequest requestBody,
                                                      HttpServletRequest request) {
        if (!userService.isAdmin(userService.getLoginUser(request))) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return ResultUtils.success(queryPage(requestBody, null));
    }

    /** 当前用户列表始终覆盖请求体 userId，防止查询其他人的图表。 */
    @PostMapping("/my/list/page")
    public BaseResponse<Page<Chart>> listMyChartByPage(@RequestBody ChartQueryRequest requestBody,
                                                        HttpServletRequest request) {
        if (requestBody == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(queryPage(requestBody, loginUser.getId()));
    }

    /** 编辑请求即使携带 chartData/结果/状态等字段，也只会更新用户可编辑字段。 */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editChart(@RequestBody ChartEditRequest requestBody, HttpServletRequest request) {
        if (requestBody == null || requestBody.getId() == null || requestBody.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart oldChart = getExistingChart(requestBody.getId());
        User loginUser = userService.getLoginUser(request);
        requireOwnerOrAdmin(oldChart, loginUser);
        Chart update = new Chart();
        update.setId(requestBody.getId());
        update.setName(requestBody.getName());
        update.setGoal(requestBody.getGoal());
        update.setChartType(requestBody.getChartType());
        return ResultUtils.success(chartService.updateById(update));
    }

    private Page<Chart> queryPage(ChartQueryRequest requestBody, Long ownerId) {
        if (requestBody == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        validatePage(requestBody);
        if (ownerId != null) {
            requestBody.setUserId(ownerId);
        }
        return chartService.page(new Page<>(requestBody.getCurrent(), requestBody.getPageSize()), buildQueryWrapper(requestBody));
    }

    private void validatePage(PageRequest requestBody) {
        if (requestBody.getCurrent() < 1 || requestBody.getPageSize() < 1 || requestBody.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数超出允许范围");
        }
    }

    private QueryWrapper<Chart> buildQueryWrapper(ChartQueryRequest requestBody) {
        QueryWrapper<Chart> query = new QueryWrapper<>();
        query.eq(requestBody.getId() != null && requestBody.getId() > 0, "id", requestBody.getId());
        query.like(StringUtils.isNotBlank(requestBody.getName()), "name", requestBody.getName());
        query.eq(StringUtils.isNotBlank(requestBody.getGoal()), "goal", requestBody.getGoal());
        query.eq(StringUtils.isNotBlank(requestBody.getChartType()), "chartType", requestBody.getChartType());
        query.eq(requestBody.getUserId() != null, "userId", requestBody.getUserId());
        query.eq("isDelete", 0);

        String sortField = requestBody.getSortField();
        if (sortField != null && SORT_FIELDS.contains(sortField)) {
            boolean ascending = !"desc".equalsIgnoreCase(requestBody.getSortOrder());
            query.orderBy(true, ascending, sortField);
        }
        return query;
    }

    private Chart getExistingChart(Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = chartService.getById(chartId);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return chart;
    }

    private void requireOwnerOrAdmin(Chart chart, User loginUser) {
        if (!loginUser.getId().equals(chart.getUserId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private boolean isRunning(String status) {
        return "RUNNING".equalsIgnoreCase(status);
    }
}
