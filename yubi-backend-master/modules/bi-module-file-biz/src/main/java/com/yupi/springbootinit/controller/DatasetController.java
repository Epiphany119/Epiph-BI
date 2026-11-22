package com.yupi.springbootinit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.DeleteRequest;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.modules.file.application.DatasetApplicationService;
import com.yupi.springbootinit.modules.file.model.vo.DatasetVO;
import com.yupi.springbootinit.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

/** 可复用 BI 数据集管理接口。 */
@RestController
@RequestMapping("/dataset")
public class DatasetController {
    private final DatasetApplicationService datasetApplicationService;
    private final UserService userService;

    public DatasetController(DatasetApplicationService datasetApplicationService, UserService userService) {
        this.datasetApplicationService = datasetApplicationService;
        this.userService = userService;
    }

    /** 上传 Excel/CSV 表格并沉淀为可复用数据集。 */
    @PostMapping("/upload")
    public BaseResponse<DatasetVO> uploadDataset(@RequestPart("file") MultipartFile file,
                                                 @RequestParam(value = "name", required = false) String name,
                                                 HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(datasetApplicationService.uploadSpreadsheet(loginUser.getId(), name, file));
    }

    /** 分页查询当前用户的数据集列表。 */
    @GetMapping("/my/list")
    public BaseResponse<Page<DatasetVO>> listMyDatasets(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize,
            @RequestParam(value = "name", required = false) String name,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(datasetApplicationService.listMyDatasets(loginUser.getId(), current, pageSize, name));
    }

    /** 查看数据集详情、列画像与前 20 行预览。 */
    @GetMapping("/get")
    public BaseResponse<DatasetVO> getDatasetDetail(@RequestParam("id") long id, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(datasetApplicationService.getDatasetDetail(id, loginUser.getId()));
    }

    /** 逻辑删除当前用户的数据集。 */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteDataset(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(datasetApplicationService.deleteDataset(deleteRequest.getId(), loginUser.getId()));
    }
}
