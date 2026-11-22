package com.yupi.springbootinit.mapper;

import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Entity com.yupi.springbootinit.model.entity.Chart
 */
public interface ChartMapper extends BaseMapper<Chart> {

    int tryStartAnalysis(@Param("chartId") Long chartId,
                         @Param("workerId") String workerId,
                         @Param("leaseSeconds") int leaseSeconds);

    int completeAnalysis(@Param("chartId") Long chartId,
                         @Param("workerId") String workerId,
                         @Param("genChart") String genChart,
                         @Param("genResult") String genResult);

    int failAnalysis(@Param("chartId") Long chartId,
                     @Param("workerId") String workerId,
                     @Param("execMessage") String execMessage);

    List<Long> findWaitingAnalysisTaskIds(@Param("limit") int limit);

    List<Chart> findExpiredRunningTasks(@Param("limit") int limit);

    int requeueExpiredRunningTask(@Param("chartId") Long chartId,
                                  @Param("expectedWorkerId") String expectedWorkerId);

    int failExpiredRunningTask(@Param("chartId") Long chartId,
                               @Param("expectedWorkerId") String expectedWorkerId,
                               @Param("execMessage") String execMessage);

    int prepareRetry(@Param("chartId") Long chartId, @Param("userId") Long userId);

    int accumulateUsage(@Param("chartId") Long chartId,
                        @Param("actualModel") String actualModel,
                        @Param("totalTokens") int totalTokens,
                        @Param("estimatedCostCny") java.math.BigDecimal estimatedCostCny,
                        @Param("latencyMs") long latencyMs);

    int deleteIfNotRunning(@Param("chartId") Long chartId);
}




