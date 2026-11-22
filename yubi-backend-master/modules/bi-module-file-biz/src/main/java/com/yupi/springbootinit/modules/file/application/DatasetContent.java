package com.yupi.springbootinit.modules.file.application;

/** 供 analysis-biz 读取的数据集受控快照，不暴露 BiDataset 持久化实体。 */
public final class DatasetContent {
    private final Long id;
    private final Long userId;
    private final String name;
    private final String originalFilename;
    private final int rowCount;
    private final int columnCount;
    private final String csvContent;

    public DatasetContent(Long id, Long userId, String name, String originalFilename,
                          int rowCount, int columnCount, String csvContent) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.originalFilename = originalFilename;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.csvContent = csvContent;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getOriginalFilename() { return originalFilename; }
    public int getRowCount() { return rowCount; }
    public int getColumnCount() { return columnCount; }
    public String getCsvContent() { return csvContent; }
}
