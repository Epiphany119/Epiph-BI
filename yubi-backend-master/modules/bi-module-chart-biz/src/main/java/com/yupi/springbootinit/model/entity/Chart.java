package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 图表信息表
 * @TableName chart
 */
@TableName(value ="chart")
@Data
public class Chart implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 名称
     */
    private String name;

    /**
     * 分析目标
     */
    private String goal;

    /**
     * 图表数据
     */
    private String chartData;

    /**
     * 图表类型
     */
    private String chartType;

    /**
     * 生成的图表数据
     */
    private String genChart;

    /**
     * 生成的分析结论
     */
    private String genResult;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 执行信息
     */
    private String execMessage;

    /**
     * 已尝试执行次数
     */
    private Integer attemptCount;

    /**
     * 最大自动尝试次数
     */
    private Integer maxAttempts;

    /**
     * RUNNING 租约过期时间
     */
    private Date leaseExpireTime;

    /**
     * 当前持有租约的执行节点标识
     */
    private String workerId;

    /**
     * 实际执行的 AI 模型
     */
    private String actualModel;

    /**
     * 累计消耗 Token 数
     */
    private Integer totalTokens;

    /**
     * 累计估算费用（人民币元）
     */
    private java.math.BigDecimal estimatedCostCny;

    /**
     * 累计 AI 调用耗时（毫秒）
     */
    private Long latencyMs;

    /**
     * 关联数据集 ID
     */
    private Long datasetId;

    /**
     * 父图表 ID（用于多轮下钻血缘）
     */
    private Long parentChartId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
