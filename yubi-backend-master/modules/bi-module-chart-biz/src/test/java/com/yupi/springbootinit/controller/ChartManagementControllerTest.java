package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.DeleteRequest;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.dto.chart.ChartEditRequest;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.dto.chart.ChartUpdateRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图表 CRUD 控制器模块测试：验证权限和字段白名单，不启动完整 Spring 应用。 */
class ChartManagementControllerTest {

    /** 普通用户不能使用全量分页接口。 */
    @Test
    void shouldRestrictFullChartListToAdministrators() {
        ChartService chartService = mock(ChartService.class);
        UserService userService = mock(UserService.class);
        User user = user(20L, "user");
        when(userService.getLoginUser(any())).thenReturn(user);
        when(userService.isAdmin(user)).thenReturn(false);
        ChartManagementController controller = new ChartManagementController(chartService, mock(ChartMapper.class), userService);

        assertThrows(BusinessException.class, () ->
                controller.listChartByPage(new ChartQueryRequest(), new MockHttpServletRequest()));

        verify(chartService, never()).page(any(), any());
    }

    /** 我的图表查询必须覆盖请求体 userId，避免通过修改参数查询别人的数据。 */
    @Test
    void shouldForceCurrentUserIdForMyChartList() {
        ChartService chartService = mock(ChartService.class);
        UserService userService = mock(UserService.class);
        User user = user(21L, "user");
        when(userService.getLoginUser(any())).thenReturn(user);
        ChartManagementController controller = new ChartManagementController(chartService, mock(ChartMapper.class), userService);
        ChartQueryRequest query = new ChartQueryRequest();
        query.setUserId(999L);

        controller.listMyChartByPage(query, new MockHttpServletRequest());

        assertEquals(21L, query.getUserId());
        verify(chartService).page(any(), any());
    }

    /** 编辑请求携带 chartData 时，服务端仍只更新名称、目标和图表类型白名单。 */
    @Test
    void shouldIgnoreChartDataFromEditRequest() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(22L)).thenReturn(chart(22L, 22L, "SUCCEEDED"));
        when(chartService.updateById(any(Chart.class))).thenReturn(true);
        UserService userService = mock(UserService.class);
        when(userService.getLoginUser(any())).thenReturn(user(22L, "user"));
        ChartManagementController controller = new ChartManagementController(chartService, mock(ChartMapper.class), userService);
        ChartEditRequest edit = new ChartEditRequest();
        edit.setId(22L);
        edit.setName("新名称");
        edit.setGoal("新目标");
        edit.setChartType("折线图");
        edit.setChartData("malicious replacement");

        controller.editChart(edit, new MockHttpServletRequest());

        org.mockito.ArgumentCaptor<Chart> updated = org.mockito.ArgumentCaptor.forClass(Chart.class);
        verify(chartService).updateById(updated.capture());
        assertEquals("新名称", updated.getValue().getName());
        assertEquals("新目标", updated.getValue().getGoal());
        assertEquals("折线图", updated.getValue().getChartType());
        assertEquals(null, updated.getValue().getChartData());
        assertEquals(null, updated.getValue().getGenChart());
        assertEquals(null, updated.getValue().getStatus());
    }

    /** 非所属用户且非管理员不能编辑图表。 */
    @Test
    void shouldRejectEditingAnotherUsersChart() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(25L)).thenReturn(chart(25L, 88L, "SUCCEEDED"));
        UserService userService = mock(UserService.class);
        User loginUser = user(25L, "user");
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(userService.isAdmin(loginUser)).thenReturn(false);
        ChartManagementController controller = new ChartManagementController(chartService, mock(ChartMapper.class), userService);
        ChartEditRequest edit = new ChartEditRequest();
        edit.setId(25L);

        assertThrows(BusinessException.class, () -> controller.editChart(edit, new MockHttpServletRequest()));

        verify(chartService, never()).updateById(any(Chart.class));
    }

    /** 管理员可以使用更新接口，但请求中的 AI 产物、图表数据和状态仍不能越过字段白名单。 */
    @Test
    void shouldAllowAdminUpdateOnlyForWhitelistedFields() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(26L)).thenReturn(chart(26L, 88L, "SUCCEEDED"));
        when(chartService.updateById(any(Chart.class))).thenReturn(true);
        UserService userService = mock(UserService.class);
        User admin = user(27L, "admin");
        when(userService.getLoginUser(any())).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);
        ChartManagementController controller = new ChartManagementController(chartService, mock(ChartMapper.class), userService);
        ChartUpdateRequest update = new ChartUpdateRequest();
        update.setId(26L);
        update.setName("管理员改名");
        update.setGoal("管理员修改目标");
        update.setChartType("折线图");
        update.setChartData("should-not-be-written");
        update.setGenChart("should-not-be-written");
        update.setGenResult("should-not-be-written");
        update.setIsDelete(1);

        controller.updateChart(update, new MockHttpServletRequest());

        org.mockito.ArgumentCaptor<Chart> updated = org.mockito.ArgumentCaptor.forClass(Chart.class);
        verify(chartService).updateById(updated.capture());
        assertEquals("管理员改名", updated.getValue().getName());
        assertEquals("管理员修改目标", updated.getValue().getGoal());
        assertEquals("折线图", updated.getValue().getChartType());
        assertEquals(null, updated.getValue().getChartData());
        assertEquals(null, updated.getValue().getGenChart());
        assertEquals(null, updated.getValue().getGenResult());
        assertEquals(null, updated.getValue().getIsDelete());
    }

    /** RUNNING 图表不得删除，即使数据库条件更新也尚未执行。 */
    @Test
    void shouldRejectDeletingRunningChart() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(23L)).thenReturn(chart(23L, 23L, "RUNNING"));
        UserService userService = mock(UserService.class);
        when(userService.getLoginUser(any())).thenReturn(user(23L, "user"));
        ChartMapper chartMapper = mock(ChartMapper.class);
        ChartManagementController controller = new ChartManagementController(chartService, chartMapper, userService);
        DeleteRequest delete = new DeleteRequest();
        delete.setId(23L);

        assertThrows(BusinessException.class, () -> controller.deleteChart(delete, new MockHttpServletRequest()));

        verify(chartMapper, never()).deleteIfNotRunning(23L);
    }

    /** 逻辑删除必须落到 Mapper 的 RUNNING 条件更新，而不是无条件 removeById。 */
    @Test
    void shouldUseConditionalLogicalDeleteForFinishedChart() {
        ChartService chartService = mock(ChartService.class);
        when(chartService.getById(24L)).thenReturn(chart(24L, 24L, "SUCCEEDED"));
        UserService userService = mock(UserService.class);
        when(userService.getLoginUser(any())).thenReturn(user(24L, "user"));
        ChartMapper chartMapper = mock(ChartMapper.class);
        when(chartMapper.deleteIfNotRunning(24L)).thenReturn(1);
        ChartManagementController controller = new ChartManagementController(chartService, chartMapper, userService);
        DeleteRequest delete = new DeleteRequest();
        delete.setId(24L);

        assertTrue(controller.deleteChart(delete, new MockHttpServletRequest()).getData());

        verify(chartMapper).deleteIfNotRunning(24L);
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private Chart chart(Long id, Long userId, String status) {
        Chart chart = new Chart();
        chart.setId(id);
        chart.setUserId(userId);
        chart.setStatus(status);
        return chart;
    }
}
