package com.yupi.springbootinit.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

/** 基于已保存数据集发起智能分析的请求体。 */
@Data
public class GenChartByDatasetRequest implements Serializable {
    private Long datasetId;
    private String name;
    private String goal;
    private String chartType;

    private static final long serialVersionUID = 1L;
}
