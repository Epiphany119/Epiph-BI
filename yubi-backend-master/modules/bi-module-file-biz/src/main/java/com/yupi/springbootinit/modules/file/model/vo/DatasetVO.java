package com.yupi.springbootinit.modules.file.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/** 前端展示用数据集视图对象（列表不返回原始 CSV 全文）。 */
@Data
public class DatasetVO implements Serializable {
    private Long id;
    private String name;
    private String originalFilename;
    private Integer rowCount;
    private Integer columnCount;
    private String schemaProfileJson;
    private List<String> headers;
    private List<List<String>> previewRows;
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
