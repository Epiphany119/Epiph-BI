package com.yupi.springbootinit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yupi.springbootinit.modules.file.model.entity.BiDataset;

/** BI 数据集 Mapper（放置在 com.yupi.springbootinit.mapper 下确保被 @MapperScan 扫描）。 */
public interface BiDatasetMapper extends BaseMapper<BiDataset> {
}
