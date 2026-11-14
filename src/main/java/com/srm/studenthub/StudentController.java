package com.srm.studenthub;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class StudentController {
    private final StudentService service;

    public StudentController(StudentService service) {
        this.service = service;
    }

    @GetMapping("/students")
    public List<Map<String, Object>> students(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String section,
        @RequestParam(required = false) String residency,
        @RequestParam(required = false) String scholarship) {
        return service.search(query, section, residency, scholarship);
    }

    @GetMapping("/students/{id}")
    public Map<String, Object> student(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/students")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(payload));
    }

    @PutMapping("/students/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return service.update(id, payload);
    }

    @DeleteMapping("/students/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return service.metadata();
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields() {
        return service.customFields();
    }

    @PostMapping("/fields")
    public ResponseEntity<Map<String, Object>> addField(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCustomField(payload));
    }

    @DeleteMapping("/fields/{key}")
    public ResponseEntity<Void> deleteField(@PathVariable String key) {
        service.deleteCustomField(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public Map<String, Object> importStudents(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Choose a workbook to import.");
        }
        return service.importWorkbook(file.getBytes(), file.getOriginalFilename());
    }
}
