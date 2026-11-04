package com.yupi.springbootinit.mapper;

import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * @Entity com.yupi.springbootinit.model.entity.Chart
 */
public interface ChartMapper extends BaseMapper<Chart> {

    int tryStartAnalysis(@Param("chartId") Long chartId);

    int completeAnalysis(@Param("chartId") Long chartId,
                         @Param("genChart") String genChart,
                         @Param("genResult") String genResult);

    int failAnalysis(@Param("chartId") Long chartId,
                     @Param("execMessage") String execMessage);

    int prepareRetry(@Param("chartId") Long chartId, @Param("userId") Long userId);

    int deleteIfNotRunning(@Param("chartId") Long chartId);
}




