package com.yupi.springbootinit.modules.file.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 可复用 BI 数据集持久化实体。 */
@TableName(value = "bi_dataset")
@Data
public class BiDataset implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String originalFilename;
    private Integer rowCount;
    private Integer columnCount;
    private String schemaProfileJson;
    private String csvContent;
    private Date createTime;
    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
