package com.srm.studenthub;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:studsrm-academic-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class AcademicApiIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void managesAttendanceMarksAndFilteredPrivateSafeReports() throws Exception {
        JsonNode directory = mapper.readTree(mvc.perform(get("/api/students?section=D"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        long firstStudent = directory.get(0).get("id").asLong();
        long secondStudent = directory.get(1).get("id").asLong();
        String suffix = UUID.randomUUID().toString().substring(0, 7);

        Map<String, Object> attendance = new LinkedHashMap<>();
        attendance.put("attendanceDate", "2026-10-12");
        attendance.put("subjectName", "Systems " + suffix);
        attendance.put("hourOrPeriod", "Period 2");
        attendance.put("facultyName", "Test Faculty");
        attendance.put("section", "D");
        MvcResult sessionResponse = mvc.perform(post("/api/attendance/sessions")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(attendance)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.records.length()", is(67)))
            .andReturn();
        long sessionId = mapper.readTree(sessionResponse.getResponse().getContentAsByteArray()).get("id").asLong();
        mvc.perform(get("/api/attendance/sessions").param("date", "2026-10-12").param("section", "D"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));

        JsonNode session = mapper.readTree(mvc.perform(get("/api/attendance/sessions/{id}", sessionId))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode record : session.get("records")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("studentId", record.get("student_id").asLong());
            value.put("status", "Present");
            value.put("remarks", "");
            if (record.get("student_id").asLong() == firstStudent) {
                value.put("status", "Late");
            }
            if (record.get("student_id").asLong() == secondStudent) {
                value.put("status", "Leave");
            }
            if (record.get("student_id").asLong() == directory.get(2).get("id").asLong()) {
                value.put("status", "Absent");
            }
            if (record.get("student_id").asLong() == directory.get(3).get("id").asLong()) {
                value.put("status", "On Duty");
            }
            records.add(value);
        }
        attendance.put("records", records);
        mvc.perform(put("/api/attendance/sessions/{id}", sessionId)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(attendance)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.records[0].status").exists());
        mvc.perform(get("/api/attendance/student/{studentId}/summary", firstStudent))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attendancePercentage").value(100.0));
        mvc.perform(get("/api/attendance/student/{studentId}/summary", secondStudent))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attendancePercentage").value(0.0));
        mvc.perform(get("/api/attendance/student/{studentId}", secondStudent).param("status", "Leave"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));
        mvc.perform(get("/api/attendance/shortage").param("section", "D"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(2)));

        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("subjectCode", "TST-" + suffix);
        subject.put("subjectName", "Testing " + suffix);
        subject.put("semester", "6");
        subject.put("academicYear", "2026-27");
        subject.put("section", "D");
        subject.put("maxInternalMarks", 40);
        subject.put("maxExternalMarks", 60);
        subject.put("maxTotalMarks", 100);
        Map<String, Object> invalidSubject = new LinkedHashMap<>(subject);
        invalidSubject.put("section", "No students");
        mvc.perform(post("/api/subjects").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(invalidSubject)))
            .andExpect(status().isBadRequest());
        invalidSubject.put("section", "D");
        invalidSubject.put("maxTotalMarks", 0);
        mvc.perform(post("/api/subjects").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(invalidSubject)))
            .andExpect(status().isBadRequest());
        MvcResult subjectResponse = mvc.perform(post("/api/subjects")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(subject)))
            .andExpect(status().isCreated()).andReturn();
        long subjectId = mapper.readTree(subjectResponse.getResponse().getContentAsByteArray()).get("id").asLong();

        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("subjectId", subjectId);
        assessment.put("assessmentName", "Internal " + suffix);
        assessment.put("assessmentType", "Internal Test 1");
        assessment.put("assessmentDate", "2026-10-10");
        assessment.put("maxMarks", 50);
        assessment.put("weightage", 25);
        MvcResult assessmentResponse = mvc.perform(post("/api/assessments")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(assessment)))
            .andExpect(status().isCreated()).andReturn();
        long assessmentId = mapper.readTree(assessmentResponse.getResponse().getContentAsByteArray()).get("id").asLong();

        Map<String, Object> firstMark = mark(firstStudent, 45, "Strong");
        Map<String, Object> secondMark = mark(secondStudent, 30, "Improve");
        Map<String, Object> bulk = new LinkedHashMap<>();
        bulk.put("assessmentId", assessmentId);
        bulk.put("records", java.util.Arrays.asList(firstMark, secondMark));
        mvc.perform(post("/api/marks/bulk")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(bulk)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved", is(2)));
        mvc.perform(get("/api/marks").param("assessmentId", String.valueOf(assessmentId)).param("missingOnly", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(65)));
        mvc.perform(get("/api/marks").param("assessmentId", String.valueOf(assessmentId)).param("studentId", String.valueOf(firstStudent)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].grade", is("O")));
        MvcResult savedMarkResult = mvc.perform(get("/api/marks")
                .param("assessmentId", String.valueOf(assessmentId))
                .param("studentId", String.valueOf(firstStudent)))
            .andExpect(status().isOk()).andReturn();
        long markId = mapper.readTree(savedMarkResult.getResponse().getContentAsByteArray()).get(0).get("mark_id").asLong();
        Map<String, Object> markUpdate = new LinkedHashMap<>();
        markUpdate.put("marksObtained", 40);
        markUpdate.put("remarks", "Reviewed");
        mvc.perform(put("/api/marks/{id}", markId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(markUpdate)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marks_obtained").value(40.0));
        Map<String, Object> weightedAssessment = new LinkedHashMap<>(assessment);
        weightedAssessment.put("assessmentName", "Weighted " + suffix);
        weightedAssessment.put("assessmentType", "Model Exam");
        weightedAssessment.put("weightage", 75);
        MvcResult weightedAssessmentResponse = mvc.perform(post("/api/assessments")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(weightedAssessment)))
            .andExpect(status().isCreated()).andReturn();
        long weightedAssessmentId = mapper.readTree(weightedAssessmentResponse.getResponse().getContentAsByteArray())
            .get("id").asLong();
        Map<String, Object> weightedBulk = new LinkedHashMap<>();
        weightedBulk.put("assessmentId", weightedAssessmentId);
        weightedBulk.put("records", java.util.Collections.singletonList(mark(firstStudent, 30, "Weighted result")));
        mvc.perform(post("/api/marks/bulk").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(weightedBulk)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved", is(1)));
        MvcResult performanceResult = mvc.perform(get("/api/marks/performance").param("section", "D"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.students[0].rank", is(1)))
            .andExpect(jsonPath("$.students[0].percentage").value(65.0))
            .andReturn();
        JsonNode performance = mapper.readTree(performanceResult.getResponse().getContentAsByteArray());
        JsonNode firstSubject = performance.get("students").get(0).get("subjects").elements().next();
        org.junit.jupiter.api.Assertions.assertEquals(65.0, firstSubject.get("totalMarks").asDouble());

        Map<String, Object> invalidMark = mark(firstStudent, 51, "");
        bulk.put("records", java.util.Collections.singletonList(invalidMark));
        mvc.perform(post("/api/marks/bulk")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(bulk)))
            .andExpect(status().isBadRequest());

        Map<String, Object> reportRequest = new LinkedHashMap<>();
        reportRequest.put("type", "directory");
        reportRequest.put("section", "D");
        MvcResult preview = mvc.perform(post("/api/reports/students/preview")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(reportRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordCount", is(67)))
            .andExpect(jsonPath("$.columns").isArray())
            .andReturn();
        String previewJson = preview.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(previewJson.contains("Aadhaar Number"));
        org.junit.jupiter.api.Assertions.assertFalse(previewJson.contains("Communication Address"));
        Map<String, Object> sensitiveReport = new LinkedHashMap<>();
        sensitiveReport.put("type", "directory");
        sensitiveReport.put("includeSensitive", true);
        sensitiveReport.put("sensitiveConfirmed", true);
        mvc.perform(post("/api/reports/students/preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(sensitiveReport)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns").isArray());
        mvc.perform(post("/api/reports/students/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Collections.singletonMap("type", "parent-contacts"))))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/reports/attendance/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Collections.singletonMap("type", "shortage"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns").isArray());
        Map<String, Object> monthly = new LinkedHashMap<>();
        monthly.put("type", "monthly-summary");
        monthly.put("startDate", "2026-10-01");
        monthly.put("endDate", "2026-10-31");
        mvc.perform(post("/api/reports/attendance/preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(monthly)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns").value(org.hamcrest.Matchers.hasItem("Month")))
            .andExpect(jsonPath("$.recordCount", is(67)));
        Map<String, Object> marksReport = new LinkedHashMap<>();
        marksReport.put("type", "assessment");
        marksReport.put("assessmentId", assessmentId);
        mvc.perform(post("/api/reports/marks/preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(marksReport)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.recordCount", is(67)));
        mvc.perform(post("/api/reports/students?format=csv").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(reportRequest)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("text/csv"));
        mvc.perform(post("/api/reports/students?format=xlsx").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(reportRequest)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(post("/api/reports/marks?format=pdf").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(marksReport)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("application/pdf"));

        mvc.perform(delete("/api/attendance/sessions/{id}", sessionId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/subjects/{id}", subjectId)).andExpect(status().isNoContent());
    }

    private static Map<String, Object> mark(long studentId, int value, String remarks) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("studentId", studentId);
        record.put("marksObtained", value);
        record.put("remarks", remarks);
        return record;
    }
}
