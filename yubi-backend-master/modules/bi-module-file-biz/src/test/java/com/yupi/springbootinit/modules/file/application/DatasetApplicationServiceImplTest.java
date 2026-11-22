package com.yupi.springbootinit.modules.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.BiDatasetMapper;
import com.yupi.springbootinit.modules.file.model.entity.BiDataset;
import com.yupi.springbootinit.modules.file.model.vo.DatasetVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 数据集资产应用服务单元测试。 */
class DatasetApplicationServiceImplTest {

    @Test
    void shouldUploadCsvAndGenerateSchemaProfileAndPreview() {
        BiDatasetMapper mapper = mock(BiDatasetMapper.class);
        when(mapper.insert(any(BiDataset.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, BiDataset.class).setId(501L);
            return 1;
        });
        DatasetApplicationServiceImpl service = new DatasetApplicationServiceImpl(mapper, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.csv", "text/csv",
                "区域,渠道,销售额\n华东,线上,1200\n华南,线下,800\n".getBytes(StandardCharsets.UTF_8));

        DatasetVO vo = service.uploadSpreadsheet(10L, "销售样例", file);

        assertEquals(501L, vo.getId());
        assertEquals("销售样例", vo.getName());
        assertEquals(2, vo.getRowCount());
        assertEquals(3, vo.getColumnCount());
        assertEquals(2, vo.getPreviewRows().size());
        assertTrue(vo.getSchemaProfileJson().contains("\"inferredType\":\"number\""));
    }

    @Test
    void shouldRejectCrossUserAccessToDatasetContent() {
        BiDatasetMapper mapper = mock(BiDatasetMapper.class);
        BiDataset dataset = new BiDataset();
        dataset.setId(502L);
        dataset.setUserId(10L);
        dataset.setName("内部表");
        dataset.setCsvContent("区域,销量\n华东,10\n");
        when(mapper.selectById(502L)).thenReturn(dataset);
        DatasetApplicationServiceImpl service = new DatasetApplicationServiceImpl(mapper, new ObjectMapper());

        assertThrows(BusinessException.class, () -> service.getOwnedDatasetContent(502L, 99L));
    }

    @Test
    void shouldRejectUnsupportedFileExtension() {
        DatasetApplicationServiceImpl service =
                new DatasetApplicationServiceImpl(mock(BiDatasetMapper.class), new ObjectMapper());
        MockMultipartFile badFile = new MockMultipartFile("file", "notes.txt", "text/plain", "abc".getBytes());

        assertThrows(BusinessException.class, () -> service.uploadSpreadsheet(10L, "测试", badFile));
    }
}
