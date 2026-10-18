package com.mindharbor.tools;

import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExcelTool {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExcelTool.class);
    private final Path file;
    public ExcelTool(@Value("${tools.excel-path}") String path){file=Path.of(path).toAbsolutePath();}
    // One service instance owns this workbook. Never point independent replicas at the same file.
    public synchronized Map<String,Object> write(Long reportId,List<String> values) {
        if(reportId==null||reportId<=0||values.size()!=12)throw new IllegalArgumentException("Invalid report");
        try {
            Files.createDirectories(file.getParent());
            try(Workbook workbook=open()) {
                Sheet sheet=workbook.getNumberOfSheets()==0?workbook.createSheet("reports"):workbook.getSheetAt(0);
                if(sheet.getPhysicalNumberOfRows()==0){Row row=sheet.createRow(0);
                    String[] headers={"报告ID","用户ID","账号","会话ID","意图","情绪标签","情绪得分","风险等级","置信度","摘要","对话内容","创建时间"};
                    for(int i=0;i<headers.length;i++)row.createCell(i).setCellValue(headers[i]);}
                DataFormatter formatter=new DataFormatter();
                for(Row row:sheet)if(row.getRowNum()>0&&row.getCell(0)!=null&&formatter.formatCellValue(row.getCell(0)).equals(reportId.toString()))
                    return Map.of("status","ALREADY_EXISTS","reportId",reportId,"artifactId","mindharbor-reports.xlsx");
                Row row=sheet.createRow(sheet.getLastRowNum()+1);
                for(int i=0;i<values.size();i++){String value=Objects.toString(values.get(i),"");
                    row.createCell(i).setCellValue(value.length()>32767?value.substring(0,32767):value);}
                Path temp=Files.createTempFile(file.getParent(),"report-",".xlsx");
                try {
                    try(var output=Files.newOutputStream(temp)){workbook.write(output);}
                    Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temp);}
                log.info("MCP Excel archived reportId={}",reportId);
                return Map.of("status","WRITTEN","reportId",reportId,"artifactId","mindharbor-reports.xlsx");
            }
        }catch(Exception ex){throw new IllegalStateException("Excel write failed",ex);}
    }
    private Workbook open() throws Exception {
        if(!Files.exists(file))return new XSSFWorkbook();
        try(var input=Files.newInputStream(file)){return WorkbookFactory.create(input);}
    }
}
