package com.yupi.springbootinit.modules.file.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.modules.file.model.vo.DatasetVO;
import org.springframework.web.multipart.MultipartFile;

/** 数据集资产受控应用服务边界。 */
public interface DatasetApplicationService {

    /** 上传 Excel/CSV 并持久化为可复用数据集。 */
    DatasetVO uploadSpreadsheet(Long userId, String customName, MultipartFile file);

    /** 将已转换的标准 CSV 沉淀为可复用数据集（供分析任务同步复用）。 */
    DatasetVO saveFromCsv(Long userId, String name, String originalFilename, String csvContent);

    /** 校验归属并返回完整 CSV 内容，供 analysis-biz 发起二次分析或多轮下钻。 */
    DatasetContent getOwnedDatasetContent(Long datasetId, Long userId);

    /** 分页查询当前用户的数据集列表（不含原始 CSV 全文）。 */
    Page<DatasetVO> listMyDatasets(Long userId, long current, long pageSize, String keyword);

    /** 获取数据集详情及前 20 行预览。 */
    DatasetVO getDatasetDetail(Long datasetId, Long userId);

    /** 逻辑删除当前用户的数据集。 */
    boolean deleteDataset(Long datasetId, Long userId);
}
