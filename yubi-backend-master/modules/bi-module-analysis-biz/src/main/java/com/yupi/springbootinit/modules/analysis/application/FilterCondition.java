package com.yupi.springbootinit.modules.analysis.application;

import java.util.ArrayList;
import java.util.List;

/** 本地确定性数据过滤条件，由 AI 规划生成并经服务端表头与操作符白名单校验后执行。 */
public class FilterCondition {
    private String field;
    private String operator;
    private List<String> values = new ArrayList<>();

    public FilterCondition() {}

    public FilterCondition(String field, String operator, List<String> values) {
        this.field = field;
        this.operator = operator;
        this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public List<String> getValues() { return values; }
    public void setValues(List<String> values) {
        this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
