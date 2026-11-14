package com.srm.studenthub;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:studsrm-smart-query-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class SmartQueryIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void parsesSafeQueriesAndManagesStudentCertificates() throws Exception {
        JsonNode directory = mapper.readTree(mvc.perform(get("/api/students"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        long studentId = directory.get(0).get("id").asLong();
        String register = directory.get(0).get("register_no").asText();
        jdbc.update("UPDATE students SET dream_company = ?, plan_after_degree = ?, residency = ?, "
                + "scholarship_status = ?, gender = ?, twelfth_study_state = ? WHERE id = ?",
            "Tata Consultancy Services", "Placement", "Hosteller", "Yes", "Female", "Tamil Nadu", studentId);

        mvc.perform(get("/api/smart-query/suggestions"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(7)));
        MvcResult catalog = mvc.perform(get("/api/certificates"))
            .andExpect(status().isOk()).andReturn();
        JsonNode catalogRows = mapper.readTree(catalog.getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assertions.assertTrue(catalogRows.size() >= 90);
        boolean hasSwiftCertificate = false;
        for (JsonNode certificate : catalogRows) {
            if ("App Development with Swift Certification".equals(certificate.get("certificateName").asText())) {
                hasSwiftCertificate = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasSwiftCertificate);
        MvcResult companyResult = mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Students interested in TCS"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", org.hamcrest.Matchers.greaterThan(0)))
            .andReturn();
        JsonNode companyMatches = mapper.readTree(companyResult.getResponse().getContentAsByteArray()).get("results");
        boolean foundCompanyMatch = false;
        for (JsonNode match : companyMatches) {
            if (register.equals(match.get("Register Number").asText())) foundCompanyMatch = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(foundCompanyMatch, "TCS query should match Tata Consultancy Services.");
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Students interested in TCS' OR 1=1 --"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(0)));
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Show hostellers with scholarships"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Show students from Section D"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(67)));
        Map<String, Object> attendance = new LinkedHashMap<>();
        attendance.put("attendanceDate", "2026-10-12");
        attendance.put("subjectName", "Smart Query test");
        attendance.put("hourOrPeriod", "Period 1");
        attendance.put("section", "D");
        JsonNode session = mapper.readTree(mvc.perform(post("/api/attendance/sessions")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(attendance)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (JsonNode record : session.get("records")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("studentId", record.get("student_id").asLong());
            value.put("status", record.get("student_id").asLong() == studentId ? "Absent" : "Present");
            value.put("remarks", "");
            records.add(value);
        }
        attendance.put("records", records);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/attendance/sessions/{id}", session.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(attendance)))
            .andExpect(status().isOk());
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Hostellers with attendance below 75%"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(1)))
            .andExpect(jsonPath("$.results[0]['Attendance Warning']", is("Critical shortage")));
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("A question about the weather"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("could not understand")));

        Map<String, Object> newCertificate = new LinkedHashMap<>();
        newCertificate.put("certificateName", "Globalization Certificate");
        newCertificate.put("issuingOrganization", "SRM");
        MvcResult certificateResponse = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(newCertificate)))
            .andExpect(status().isCreated()).andReturn();
        long certificateId = mapper.readTree(certificateResponse.getResponse().getContentAsByteArray())
            .get("id").asLong();
        Map<String, Object> studentCertificate = new LinkedHashMap<>();
        studentCertificate.put("status", "Completed");
        studentCertificate.put("completionDate", "2026-10-15");
        studentCertificate.put("certificateNumber", "CERT-123");
        studentCertificate.put("remarks", "Verified");
        mvc.perform(post("/api/students/{studentId}/certificates", studentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(withCertificate(studentCertificate, certificateId))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("Completed")));
        MvcResult studentCertificates = mvc.perform(get("/api/students/{studentId}/certificates", studentId))
            .andExpect(status().isOk()).andReturn();
        boolean hasSavedStudentCertificate = false;
        for (JsonNode record : mapper.readTree(studentCertificates.getResponse().getContentAsByteArray())) {
            if (record.get("certificateId").asLong() == certificateId
                    && "CERT-123".equals(record.get("certificateNumber").asText())) {
                hasSavedStudentCertificate = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasSavedStudentCertificate);
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("How many students completed the Globalization Certificate?"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(1)))
            .andExpect(jsonPath("$.results[0]['Certificate Status']", is("Completed")));
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Show students who have not completed the Globalization Certificate"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(66)));
        studentCertificate.put("status", "In Progress");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/students/{studentId}/certificates/{certificateId}", studentId, certificateId)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(studentCertificate)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("In Progress")));
        MvcResult updatedCertificateQuery = mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("How many students are pursuing the Globalization Certificate?"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(1)))
            .andExpect(jsonPath("$.results[0]['Certificate Status']", is("In Progress")))
            .andReturn();
        JsonNode updatedCertificateMatches = mapper.readTree(updatedCertificateQuery.getResponse().getContentAsByteArray())
            .get("results");
        org.junit.jupiter.api.Assertions.assertEquals(register,
            updatedCertificateMatches.get(0).get("Register Number").asText());
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Show students pursuing the Globalization Certificate with registration number " + register))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(1)))
            .andExpect(jsonPath("$.appliedFilters['Register Number']", is(register)))
            .andExpect(jsonPath("$.results[0]['Register Number']", is(register)))
            .andExpect(jsonPath("$.results[0]['Certificate Status']", is("In Progress")));

        Map<String, Object> subject = new LinkedHashMap<>();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        subject.put("subjectCode", "JAVA-" + suffix);
        subject.put("subjectName", "Java");
        subject.put("semester", "6");
        subject.put("academicYear", "2026-27");
        subject.put("section", "D");
        subject.put("maxInternalMarks", 40);
        subject.put("maxExternalMarks", 60);
        subject.put("maxTotalMarks", 100);
        JsonNode savedSubject = mapper.readTree(mvc.perform(post("/api/subjects").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(subject))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsByteArray());
        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("subjectId", savedSubject.get("id").asLong());
        assessment.put("assessmentName", "Internal Test 1");
        assessment.put("assessmentType", "Internal Test 1");
        assessment.put("maxMarks", 100);
        assessment.put("weightage", 100);
        JsonNode savedAssessment = mapper.readTree(mvc.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(assessment))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsByteArray());
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("studentId", studentId);
        mark.put("marksObtained", 45);
        Map<String, Object> bulkMarks = new LinkedHashMap<>();
        bulkMarks.put("assessmentId", savedAssessment.get("id").asLong());
        bulkMarks.put("records", Arrays.asList(mark));
        mvc.perform(post("/api/marks/bulk").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(bulkMarks)))
            .andExpect(status().isOk());
        mvc.perform(post("/api/smart-query").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Show students with marks below 50% in Java"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count", is(1)))
            .andExpect(jsonPath("$.results[0].Percentage", is(45.0)));
        mvc.perform(post("/api/smart-query/export?format=csv").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Students interested in TCS"))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("text/csv"));
        mvc.perform(post("/api/smart-query/export?format=xlsx").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Students interested in TCS"))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(post("/api/smart-query/export?format=pdf").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(query("Students interested in TCS"))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("application/pdf"));
    }

    private static Map<String, Object> query(String value) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", value);
        return request;
    }

    private static Map<String, Object> withCertificate(Map<String, Object> record, long certificateId) {
        Map<String, Object> request = new LinkedHashMap<>(record);
        request.put("certificateId", certificateId);
        return request;
    }
}
