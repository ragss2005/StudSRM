package com.srm.studenthub;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SmartQueryController {
    private final SmartQueryService service;
    private final ReportService reports;

    public SmartQueryController(SmartQueryService service, ReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @PostMapping("/smart-query")
    public Map<String, Object> query(@RequestBody Map<String, Object> request) {
        return service.execute(request.get("query") == null ? "" : String.valueOf(request.get("query")));
    }

    @GetMapping("/smart-query/suggestions")
    public List<String> suggestions() {
        return service.suggestions();
    }

    @PostMapping("/smart-query/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "xlsx") String format,
                                         @RequestBody Map<String, Object> request) {
        Map<String, Object> result = service.execute(request.get("query") == null ? "" : String.valueOf(request.get("query")));
        String category = String.valueOf(result.get("category"));
        ReportService.ExportFile file = reports.exportRows("Smart Query · " + result.get("interpretedAs"),
            result.get("query") + " · " + result.get("appliedFilters"), castColumns(result.get("columns")),
            castRows(result.get("results")), format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.fileName, StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes.length);
        headers.add("X-StudSRM-Query-Category", category);
        return ResponseEntity.ok().headers(headers).body(file.bytes);
    }

    @GetMapping("/certificates")
    public List<Map<String, Object>> certificates() {
        return service.certificates();
    }

    @PostMapping("/certificates")
    public ResponseEntity<Map<String, Object>> createCertificate(@RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveCertificate(null, request));
    }

    @PutMapping("/certificates/{id}")
    public Map<String, Object> updateCertificate(@PathVariable long id, @RequestBody Map<String, Object> request) {
        return service.saveCertificate(id, request);
    }

    @DeleteMapping("/certificates/{id}")
    public ResponseEntity<Void> deleteCertificate(@PathVariable long id) {
        service.deleteCertificate(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/students/{studentId}/certificates")
    public List<Map<String, Object>> studentCertificates(@PathVariable long studentId) {
        return service.studentCertificates(studentId);
    }

    @PostMapping("/students/{studentId}/certificates")
    public ResponseEntity<Map<String, Object>> createStudentCertificate(@PathVariable long studentId,
                                                                         @RequestBody Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveStudentCertificate(studentId,
            requiredId(request.get("certificateId")), request));
    }

    @PutMapping("/students/{studentId}/certificates/{certificateId}")
    public Map<String, Object> updateStudentCertificate(@PathVariable long studentId, @PathVariable long certificateId,
                                                         @RequestBody Map<String, Object> request) {
        return service.saveStudentCertificate(studentId, certificateId, request);
    }

    @DeleteMapping("/students/{studentId}/certificates/{certificateId}")
    public ResponseEntity<Void> deleteStudentCertificate(@PathVariable long studentId, @PathVariable long certificateId) {
        service.deleteStudentCertificate(studentId, certificateId);
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castColumns(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static long requiredId(Object value) {
        if (!(value instanceof Number) || ((Number) value).longValue() < 1) {
            throw new IllegalArgumentException("Certificate is required.");
        }
        return ((Number) value).longValue();
    }
}
