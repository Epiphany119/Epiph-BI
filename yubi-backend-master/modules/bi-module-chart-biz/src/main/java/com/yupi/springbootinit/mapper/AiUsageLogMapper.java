package com.yupi.springbootinit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yupi.springbootinit.model.entity.AiUsageLog;
import com.yupi.springbootinit.model.vo.AiUsageDailyTrendVO;
import com.yupi.springbootinit.model.vo.AiUsageSummaryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** AI 调用用量与成本日志 Mapper。 */
public interface AiUsageLogMapper extends BaseMapper<AiUsageLog> {

    AiUsageSummaryVO selectUserUsageSummary(@Param("userId") Long userId);

    List<AiUsageDailyTrendVO> selectUserDailyTrend(@Param("userId") Long userId, @Param("days") int days);
}
