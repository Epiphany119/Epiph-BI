package com.yupi.springbootinit.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

/** 基于已有图表发起多轮追问下钻的请求体。 */
@Data
public class FollowUpChartRequest implements Serializable {
    private Long parentChartId;
    private String goal;
    private String chartType;
    private String name;

    private static final long serialVersionUID = 1L;
}
