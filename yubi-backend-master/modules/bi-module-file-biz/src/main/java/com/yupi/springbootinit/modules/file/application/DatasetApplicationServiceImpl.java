package com.yupi.springbootinit.modules.file.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.BiDatasetMapper;
import com.yupi.springbootinit.modules.file.model.entity.BiDataset;
import com.yupi.springbootinit.modules.file.model.vo.DatasetVO;
import com.yupi.springbootinit.utils.ExcelUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 数据集资产应用服务实现：负责表格解析、列画像提取、归属校验与复用查询。 */
@Service
public class DatasetApplicationServiceImpl implements DatasetApplicationService {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int PREVIEW_ROW_LIMIT = 20;
    private static final long MAX_PAGE_SIZE = 20;

    private final BiDatasetMapper biDatasetMapper;
    private final ObjectMapper objectMapper;

    public DatasetApplicationServiceImpl(BiDatasetMapper biDatasetMapper, ObjectMapper objectMapper) {
        this.biDatasetMapper = biDatasetMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public DatasetVO uploadSpreadsheet(Long userId, String customName, MultipartFile file) {
        requireUserId(userId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小不能超过 5MB");
        }
        String originalFilename = StringUtils.defaultIfBlank(file.getOriginalFilename(), "dataset.csv").trim();
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        String csvContent;
        if (lower.endsWith(".csv")) {
            try {
                csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "CSV 文件读取失败");
            }
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            csvContent = ExcelUtils.excelToCsv(file);
        } else {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持上传 .csv、.xlsx 或 .xls 格式的表格文件");
        }
        String datasetName = StringUtils.isNotBlank(customName)
                ? customName.trim()
                : stripExtension(originalFilename);
        return saveFromCsv(userId, datasetName, originalFilename, csvContent);
    }

    @Override
    @Transactional
    public DatasetVO saveFromCsv(Long userId, String name, String originalFilename, String csvContent) {
        requireUserId(userId);
        if (StringUtils.isBlank(csvContent)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "表格内容为空，无法创建数据集");
        }
        ParsedCsv parsed = parseCsv(csvContent);
        if (parsed.rows.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "表格缺少数据行，无法创建数据集");
        }
        String normalizedName = StringUtils.defaultIfBlank(name, stripExtension(originalFilename)).trim();
        if (normalizedName.length() > 120) {
            normalizedName = normalizedName.substring(0, 120);
        }
        String safeFilename = StringUtils.defaultIfBlank(originalFilename, normalizedName + ".csv").trim();
        if (safeFilename.length() > 250) {
            safeFilename = safeFilename.substring(0, 250);
        }

        BiDataset dataset = new BiDataset();
        dataset.setUserId(userId);
        dataset.setName(normalizedName);
        dataset.setOriginalFilename(safeFilename);
        dataset.setRowCount(parsed.rows.size());
        dataset.setColumnCount(parsed.headers.size());
        dataset.setSchemaProfileJson(buildSchemaProfileJson(parsed));
        dataset.setCsvContent(csvContent);
        biDatasetMapper.insert(dataset);

        return toVo(dataset, parsed.headers, slicePreview(parsed.rows));
    }

    @Override
    public DatasetContent getOwnedDatasetContent(Long datasetId, Long userId) {
        BiDataset dataset = requireOwnedDataset(datasetId, userId);
        return new DatasetContent(
                dataset.getId(),
                dataset.getUserId(),
                dataset.getName(),
                dataset.getOriginalFilename(),
                dataset.getRowCount() == null ? 0 : dataset.getRowCount(),
                dataset.getColumnCount() == null ? 0 : dataset.getColumnCount(),
                dataset.getCsvContent());
    }

    @Override
    public Page<DatasetVO> listMyDatasets(Long userId, long current, long pageSize, String keyword) {
        requireUserId(userId);
        if (current < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数超出允许范围");
        }
        QueryWrapper<BiDataset> query = new QueryWrapper<>();
        query.select("id", "userId", "name", "originalFilename", "rowCount", "columnCount",
                "schemaProfileJson", "createTime", "updateTime", "isDelete");
        query.eq("userId", userId);
        query.eq("isDelete", 0);
        if (StringUtils.isNotBlank(keyword)) {
            query.like("name", keyword.trim());
        }
        query.orderByDesc("createTime");

        Page<BiDataset> entityPage = biDatasetMapper.selectPage(new Page<>(current, pageSize), query);
        Page<DatasetVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        List<DatasetVO> records = new ArrayList<>();
        if (entityPage.getRecords() != null) {
            for (BiDataset item : entityPage.getRecords()) {
                records.add(toVo(item, null, null));
            }
        }
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public DatasetVO getDatasetDetail(Long datasetId, Long userId) {
        BiDataset dataset = requireOwnedDataset(datasetId, userId);
        ParsedCsv parsed = parseCsv(dataset.getCsvContent());
        return toVo(dataset, parsed.headers, slicePreview(parsed.rows));
    }

    @Override
    @Transactional
    public boolean deleteDataset(Long datasetId, Long userId) {
        BiDataset dataset = requireOwnedDataset(datasetId, userId);
        return biDatasetMapper.deleteById(dataset.getId()) == 1;
    }

    private BiDataset requireOwnedDataset(Long datasetId, Long userId) {
        requireUserId(userId);
        if (datasetId == null || datasetId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据集 ID 无效");
        }
        BiDataset dataset = biDatasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "数据集不存在");
        }
        if (!userId.equals(dataset.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该数据集");
        }
        return dataset;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不能为空");
        }
    }

    private DatasetVO toVo(BiDataset dataset, List<String> headers, List<List<String>> previewRows) {
        DatasetVO vo = new DatasetVO();
        vo.setId(dataset.getId());
        vo.setName(dataset.getName());
        vo.setOriginalFilename(dataset.getOriginalFilename());
        vo.setRowCount(dataset.getRowCount());
        vo.setColumnCount(dataset.getColumnCount());
        vo.setSchemaProfileJson(dataset.getSchemaProfileJson());
        vo.setHeaders(headers);
        vo.setPreviewRows(previewRows);
        vo.setCreateTime(dataset.getCreateTime());
        return vo;
    }

    private List<List<String>> slicePreview(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.min(rows.size(), PREVIEW_ROW_LIMIT);
        return new ArrayList<>(rows.subList(0, limit));
    }

    private ParsedCsv parseCsv(String csvContent) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csvContent))) {
            java.util.Iterator<CSVRecord> iterator = parser.iterator();
            if (!iterator.hasNext()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "表格中没有可解析的表头");
            }
            CSVRecord headerRecord = iterator.next();
            for (int i = 0; i < headerRecord.size(); i++) {
                String raw = headerRecord.get(i);
                String header = raw == null ? "" : raw.replace("\uFEFF", "").trim();
                headers.add(header.isEmpty() ? "列" + (i + 1) : header);
            }
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next();
                List<String> row = new ArrayList<>(headers.size());
                boolean nonEmptyRow = false;
                for (int i = 0; i < headers.size(); i++) {
                    String cell = i < record.size() && record.get(i) != null ? record.get(i).trim() : "";
                    if (!cell.isEmpty()) nonEmptyRow = true;
                    row.add(cell);
                }
                if (nonEmptyRow) {
                    rows.add(row);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "CSV 解析失败: " + e.getMessage());
        }
        return new ParsedCsv(headers, rows);
    }

    private String buildSchemaProfileJson(ParsedCsv parsed) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int col = 0; col < parsed.headers.size(); col++) {
            int nonEmpty = 0;
            int numeric = 0;
            Set<String> distinct = new LinkedHashSet<>();
            for (List<String> row : parsed.rows) {
                String val = row.get(col);
                if (val.isEmpty()) continue;
                nonEmpty++;
                distinct.add(val);
                if (isNumber(val)) numeric++;
            }
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", parsed.headers.get(col));
            profile.put("inferredType", nonEmpty > 0 && numeric >= Math.ceil(nonEmpty * 0.8d) ? "number" : "text_or_category");
            profile.put("nonEmptyCount", nonEmpty);
            profile.put("distinctCount", distinct.size());
            fields.add(profile);
        }
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private boolean isNumber(String raw) {
        String normalized = raw.replace(",", "").replace("￥", "").replace("¥", "").replace("$", "");
        try {
            new BigDecimal(normalized);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String stripExtension(String filename) {
        if (StringUtils.isBlank(filename)) return "未命名数据集";
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static final class ParsedCsv {
        private final List<String> headers;
        private final List<List<String>> rows;

        private ParsedCsv(List<String> headers, List<List<String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }
    }
}
