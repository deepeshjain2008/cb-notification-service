package com.igot.cb.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FileProcessServiceTest {

    @InjectMocks
    private FileProcessService fileProcessService;

    @Before
    public void setUp() {
        fileProcessService = new FileProcessService();
    }

    @Test
    public void testProcessExcelFile_Success() throws IOException {
        MultipartFile file = createTestExcelFile();
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should have 2 data rows", 2, result.size());
        assertEquals("First row, first column should match", "Value1", result.get(0).get("Header1"));
        assertEquals("First row, second column should match", "Value2", result.get(0).get("Header2"));
        assertEquals("Second row, first column should match", "Value3", result.get(1).get("Header1"));
        assertEquals("Second row, second column should match", "Value4", result.get(1).get("Header2"));
    }

    @Test
    public void testProcessCsvFile_Success() throws IOException {
        MultipartFile file = createTestCsvFile();
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should have 2 data rows", 2, result.size());
        assertEquals("First row, first column should match", "Value1", result.get(0).get("Header1"));
        assertEquals("First row, second column should match", "Value2", result.get(0).get("Header2"));
        assertEquals("Second row, first column should match", "Value3", result.get(1).get("Header1"));
        assertEquals("Second row, second column should match", "Value4", result.get(1).get("Header2"));
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_NullFileName() {
        MockMultipartFile file = new MockMultipartFile("file", null, "application/vnd.ms-excel", new byte[0]);
        fileProcessService.processExcelFile(file);
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_UnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "test content".getBytes());
        fileProcessService.processExcelFile(file);
    }

    @Test
    public void testProcessExcelFile_EmptyRows() throws IOException {
        MultipartFile file = createExcelFileWithEmptyRows();
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should have 1 data row (ignoring empty rows)", 1, result.size());
        assertEquals("First row, first column should match", "Value1", result.get(0).get("Header1"));
    }

    @Test
    public void testProcessExcelFile_WithDates() throws IOException {
        MultipartFile file = createExcelFileWithDates();
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should have 1 data row", 1, result.size());
        assertTrue("Date should be formatted correctly", 
                result.get(0).get("Date").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    private MultipartFile createTestExcelFile() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        Cell headerCell1 = headerRow.createCell(0);
        headerCell1.setCellValue("Header1");
        Cell headerCell2 = headerRow.createCell(1);
        headerCell2.setCellValue("Header2");
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        dataRow1.createCell(1).setCellValue("Value2");
        Row dataRow2 = sheet.createRow(2);
        dataRow2.createCell(0).setCellValue("Value3");
        dataRow2.createCell(1).setCellValue("Value4");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        return new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
                byteArrayOutputStream.toByteArray());
    }
    
    private MultipartFile createTestCsvFile() {
        String csvContent = "Header1,Header2\nValue1,Value2\nValue3,Value4";
        return new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());
    }
    
    private MultipartFile createExcelFileWithEmptyRows() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        Cell headerCell1 = headerRow.createCell(0);
        headerCell1.setCellValue("Header1");
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        sheet.createRow(2);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        return new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
                byteArrayOutputStream.toByteArray());
    }
    
    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_TrulyNullFileName_viaMock() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);
        fileProcessService.processExcelFile(file);
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_IOExceptionOnGetInputStream() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("test.xlsx");
        when(file.getInputStream()).thenThrow(new IOException("boom"));
        fileProcessService.processExcelFile(file);
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_IOExceptionOnStreamClose() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Header1");
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        byte[] content = byteArrayOutputStream.toByteArray();

        InputStream failingCloseStream = new ByteArrayInputStream(content) {
            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("test.xlsx");
        when(file.getInputStream()).thenReturn(failingCloseStream);
        fileProcessService.processExcelFile(file);
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_CorruptWorkbookContentThrowsIOException() {
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "this is not a real workbook".getBytes());
        fileProcessService.processExcelFile(file);
    }

    @Test
    public void testProcessExcelFile_XlsExtension_Success() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Header1");
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        MultipartFile file = new MockMultipartFile("file", "test.xls",
                "application/vnd.ms-excel", byteArrayOutputStream.toByteArray());
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should have 1 data row", 1, result.size());
        assertEquals("Value1", result.get(0).get("Header1"));
    }

    @Test
    public void testProcessExcelFile_NullDataRowBreaksLoop() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Header1");
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        // Skip row index 2 entirely (gap), but create row 3 so getLastRowNum() extends past the gap.
        Row dataRow3 = sheet.createRow(3);
        dataRow3.createCell(0).setCellValue("Value3");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                byteArrayOutputStream.toByteArray());
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should stop at the null row and only have the first data row", 1, result.size());
        assertEquals("Value1", result.get(0).get("Header1"));
    }

    @Test(expected = RuntimeException.class)
    public void testProcessExcelFile_MissingHeaderRowThrowsException() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        // No header row (row 0) created at all; only a data row at index 1.
        Row dataRow1 = sheet.createRow(1);
        dataRow1.createCell(0).setCellValue("Value1");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                byteArrayOutputStream.toByteArray());
        fileProcessService.processExcelFile(file);
    }

    @Test
    public void testProcessExcelFile_HeaderAndValueCellEdgeCases() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("H1");
        // index 1 intentionally left as a gap: headerRow.getCell(1) will be null.
        headerRow.createCell(2); // blank header cell (no value set).
        headerRow.createCell(3).setCellValue("H2");
        headerRow.createCell(4).setCellValue("H3");

        Row dataRow = sheet.createRow(1);
        dataRow.createCell(0).setCellValue("V1");
        // index 1 and 2 are skipped on purpose; their header cells are null/blank so the
        // value cells are never inspected.
        dataRow.createCell(3); // blank value cell (no value set) paired with valid header H2.
        dataRow.createCell(4).setCellValue(42.0); // numeric, but NOT date-formatted.

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        MultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                byteArrayOutputStream.toByteArray());
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals(1, result.size());
        assertEquals("V1", result.get(0).get("H1"));
        assertEquals("", result.get(0).get("H2"));
        assertEquals("42", result.get(0).get("H3"));
    }

    @Test
    public void testProcessCsvFile_WithDateValue() throws IOException {
        String csvContent = "Header1,Header2\nValue1,2024-01-01T10:15:30.123Z";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals(1, result.size());
        assertTrue("Date should be formatted correctly",
                result.get(0).get("Header2").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    public void testProcessCsvFile_AllBlankRowBreaksProcessing() throws IOException {
        String csvContent = "Header1,Header2\nValue1,Value2\n , \nValue3,Value4";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());
        List<Map<String, String>> result = fileProcessService.processExcelFile(file);
        assertNotNull("Result should not be null", result);
        assertEquals("Should stop at the blank row and ignore rows after it", 1, result.size());
        assertEquals("Value1", result.get(0).get("Header1"));
    }

    @Test(expected = RuntimeException.class)
    public void testProcessCsvFile_MalformedRowThrowsException() throws IOException {
        String csvContent = "Header1,Header2\nValue1";
        MultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());
        fileProcessService.processExcelFile(file);
    }

    private MultipartFile createExcelFileWithDates() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        Cell headerCell = headerRow.createCell(0);
        headerCell.setCellValue("Date");
        Row dataRow = sheet.createRow(1);
        Cell dateCell = dataRow.createCell(0);
        CellStyle cellStyle = workbook.createCellStyle();
        CreationHelper createHelper = workbook.getCreationHelper();
        cellStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));
        dateCell.setCellStyle(cellStyle);
        dateCell.setCellValue(new java.util.Date());
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        workbook.write(byteArrayOutputStream);
        workbook.close();
        return new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
                byteArrayOutputStream.toByteArray());
    }
}