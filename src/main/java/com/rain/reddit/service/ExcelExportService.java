package com.rain.reddit.service;

import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.domain.ProfileRecord;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ExcelExportService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private final Path outputDirectory;

    public ExcelExportService(CrawlerProperties properties) {
        this.outputDirectory = properties.outputDirectory().toAbsolutePath().normalize();
    }

    public Path export(List<ProfileRecord> profiles) throws IOException {
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(FILE_TIME.format(LocalDateTime.now()) + ".xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Reddit Profiles");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            String[] columns = {"user_id", "profile_description", "social_links"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
                header.getCell(i).setCellStyle(headerStyle);
            }

            int rowNumber = 1;
            for (ProfileRecord profile : profiles) {
                Row row = sheet.createRow(rowNumber++);
                row.createCell(0).setCellValue(profile.userId());
                row.createCell(1).setCellValue(profile.description());
                row.createCell(2).setCellValue(String.join("\n", profile.socialLinks()));
            }
            sheet.setColumnWidth(0, 24 * 256);
            sheet.setColumnWidth(1, 70 * 256);
            sheet.setColumnWidth(2, 60 * 256);
            sheet.createFreezePane(0, 1);

            try (OutputStream stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
        return output;
    }
}
