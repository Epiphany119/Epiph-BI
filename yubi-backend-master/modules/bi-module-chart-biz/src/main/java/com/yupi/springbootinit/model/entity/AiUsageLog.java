package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** AI 调用用量与成本日志实体。 */
@TableName(value = "ai_usage_log")
@Data
public class AiUsageLog implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long chartId;
    private String stage;
    private String requestedModel;
    private String actualModel;
    private Integer fallbackUsed;
    private Integer attemptCount;
    private Integer success;
    private String errorCode;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private BigDecimal estimatedCostCny;
    private Long latencyMs;
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
