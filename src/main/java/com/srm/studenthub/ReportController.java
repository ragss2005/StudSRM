package com.srm.studenthub;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/{category}/preview")
    public Map<String, Object> preview(@PathVariable String category, @RequestBody Map<String, Object> request) {
        return reports.preview(category, request);
    }

    @PostMapping("/{category}")
    public ResponseEntity<byte[]> export(@PathVariable String category,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestBody Map<String, Object> request) {
        ReportService.ExportFile file = reports.export(category, request, format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(file.fileName, StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes.length);
        return ResponseEntity.ok().headers(headers).body(file.bytes);
    }
}
