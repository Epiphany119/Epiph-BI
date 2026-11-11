package com.yupi.springbootinit.modules.analysis.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 一个堆叠图系列及其按横轴类别对齐的统计值。 */
public class AnalysisSeries {
    private String name;
    private List<BigDecimal> values = new ArrayList<>();

    public AnalysisSeries() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<BigDecimal> getValues() { return values; }
    public void setValues(List<BigDecimal> values) { this.values = values; }
}
