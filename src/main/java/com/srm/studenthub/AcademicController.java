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

@RestController
@RequestMapping("/api")
public class AcademicController {
    private final AcademicService service;

    public AcademicController(AcademicService service) {
        this.service = service;
    }

    @GetMapping("/attendance/sessions")
    public List<Map<String, Object>> attendanceSessions(@RequestParam Map<String, String> filters) {
        return service.attendanceSessions(filters);
    }

    @GetMapping("/attendance/sessions/{id}")
    public Map<String, Object> attendanceSession(@PathVariable long id) {
        return service.attendanceSession(id);
    }

    @PostMapping("/attendance/sessions")
    public ResponseEntity<Map<String, Object>> createAttendance(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveAttendance(null, payload));
    }

    @PutMapping("/attendance/sessions/{id}")
    public Map<String, Object> updateAttendance(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return service.saveAttendance(id, payload);
    }

    @DeleteMapping("/attendance/sessions/{id}")
    public ResponseEntity<Void> deleteAttendance(@PathVariable long id) {
        service.deleteAttendanceSession(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/attendance/student/{studentId}")
    public List<Map<String, Object>> studentAttendance(@PathVariable long studentId,
                                                       @RequestParam Map<String, String> filters) {
        return service.attendanceHistory(studentId, filters);
    }

    @GetMapping("/attendance/student/{studentId}/summary")
    public Map<String, Object> studentAttendanceSummary(@PathVariable long studentId) {
        return service.studentAttendanceSummary(studentId);
    }

    @GetMapping("/attendance/shortage")
    public List<Map<String, Object>> attendanceShortage(@RequestParam(required = false) String section) {
        return service.attendanceShortage(section);
    }

    @GetMapping("/attendance/stats")
    public List<Map<String, Object>> attendanceStats(@RequestParam(required = false) String section,
                                                     @RequestParam(required = false) String startDate,
                                                     @RequestParam(required = false) String endDate) {
        return service.attendanceStats(section, startDate, endDate);
    }

    @GetMapping("/attendance/dashboard")
    public Map<String, Object> attendanceDashboard(@RequestParam Map<String, String> filters) {
        return service.attendanceDashboard(filters);
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects(@RequestParam Map<String, String> filters) {
        return service.subjects(filters);
    }

    @PostMapping("/subjects")
    public ResponseEntity<Map<String, Object>> createSubject(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveSubject(null, payload));
    }

    @PutMapping("/subjects/{id}")
    public Map<String, Object> updateSubject(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return service.saveSubject(id, payload);
    }

    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Void> deleteSubject(@PathVariable long id) {
        service.deleteSubject(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assessments")
    public List<Map<String, Object>> assessments(@RequestParam Map<String, String> filters) {
        return service.assessments(filters);
    }

    @GetMapping("/assessment-types")
    public List<String> assessmentTypes() {
        return service.assessmentTypes();
    }

    @PostMapping("/assessments")
    public ResponseEntity<Map<String, Object>> createAssessment(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveAssessment(null, payload));
    }

    @PutMapping("/assessments/{id}")
    public Map<String, Object> updateAssessment(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return service.saveAssessment(id, payload);
    }

    @DeleteMapping("/assessments/{id}")
    public ResponseEntity<Void> deleteAssessment(@PathVariable long id) {
        service.deleteAssessment(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/marks")
    public List<Map<String, Object>> marks(@RequestParam Map<String, String> filters) {
        return service.marks(filters);
    }

    @PostMapping("/marks/bulk")
    public Map<String, Object> saveBulkMarks(@RequestBody Map<String, Object> payload) {
        return service.saveBulkMarks(payload);
    }

    @PutMapping("/marks/{id}")
    public Map<String, Object> updateMark(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        return service.updateMark(id, payload);
    }

    @DeleteMapping("/marks/{id}")
    public ResponseEntity<Void> deleteMark(@PathVariable long id) {
        service.deleteMark(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/marks/student/{studentId}")
    public List<Map<String, Object>> studentMarks(@PathVariable long studentId) {
        return service.studentMarks(studentId);
    }

    @GetMapping("/marks/performance")
    public Map<String, Object> marksPerformance(@RequestParam Map<String, String> filters) {
        return service.marksPerformance(filters);
    }
}
