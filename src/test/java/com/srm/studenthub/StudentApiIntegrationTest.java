package com.srm.studenthub;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:studsrm-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class StudentApiIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void seedsAndManagesStudentsAndImportsWithoutDuplicates() throws Exception {
        mvc.perform(get("/api/metadata"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalStudents", is(67)))
            .andExpect(jsonPath("$.sectionCount", is(1)));
        MvcResult directoryResult = mvc.perform(get("/api/students"))
            .andExpect(status().isOk()).andReturn();
        com.fasterxml.jackson.databind.JsonNode directory =
            mapper.readTree(directoryResult.getResponse().getContentAsByteArray());
        for (int i = 1; i < directory.size(); i++) {
            org.junit.jupiter.api.Assertions.assertTrue(
                directory.get(i - 1).get("register_no").asText()
                    .compareToIgnoreCase(directory.get(i).get("register_no").asText()) <= 0,
                "Students should be listed in register-number order.");
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String register = "TEST-" + suffix;
        String importedRegister = "IMPORT-" + suffix;
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("register_no", register);
        initial.put("admission_no", "ADM-" + suffix);
        initial.put("first_name", "Test");
        initial.put("last_name", "Student");
        initial.put("official_email", "test-" + suffix + "@example.edu");
        initial.put("section", "D");
        initial.put("residency", "Hosteller");
        initial.put("scholarship_status", "Yes");
        initial.put("aadhaar_number", "private-test-value");
        initial.put("customFields", new LinkedHashMap<>());

        MvcResult created = mvc.perform(post("/api/students")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(initial)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.register_no", is(register.toUpperCase(Locale.ROOT))))
            .andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();

        Map<String, Object> caseVariant = new LinkedHashMap<>(initial);
        caseVariant.put("register_no", register.toLowerCase(Locale.ROOT));
        mvc.perform(post("/api/students")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(caseVariant)))
            .andExpect(status().isConflict());

        mvc.perform(get("/api/students")
                .param("query", register)
                .param("section", "D")
                .param("residency", "hosteller")
                .param("scholarship", "yes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andExpect(jsonPath("$[0].register_no", is(register.toUpperCase(Locale.ROOT))))
            .andExpect(jsonPath("$[0].aadhaar_number").doesNotExist());

        mvc.perform(get("/api/students").param("query", "Test Student"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));
        mvc.perform(get("/api/students").param("query", "ADM-" + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));
        mvc.perform(get("/api/students").param("query", "test-" + suffix + "@example.edu"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));

        mvc.perform(get("/api/students/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aadhaar_number", is("private-test-value")));

        String fieldKey = "test_attendance_" + suffix.toLowerCase();
        Map<String, Object> fieldRequest = new LinkedHashMap<>();
        fieldRequest.put("label", "Test Attendance " + suffix);
        fieldRequest.put("type", "number");
        mvc.perform(post("/api/fields")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(fieldRequest)))
            .andExpect(status().isCreated());

        Map<String, Object> update = new LinkedHashMap<>(initial);
        update.put("first_name", "Updated");
        update.put("customFields", Collections.singletonMap(fieldKey, "96"));
        mvc.perform(put("/api/students/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.first_name", is("Updated")))
            .andExpect(jsonPath("$.customFields." + fieldKey, is("96")));

        byte[] workbook = importWorkbook(register, importedRegister);
        MockMultipartFile file = new MockMultipartFile("file", "students.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        mvc.perform(multipart("/api/import").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted", is(1)))
            .andExpect(jsonPath("$.updated", is(1)))
            .andExpect(jsonPath("$.importedOrRefreshed", is(2)));

        mvc.perform(get("/api/students").param("query", register))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));
        mvc.perform(get("/api/students/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.first_name", is("From import")))
            .andExpect(jsonPath("$.customFields." + fieldKey, is("96")));
        mvc.perform(get("/api/students")
                .param("query", register)
                .param("residency", "dayscholar")
                .param("scholarship", "no"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)));

        mvc.perform(delete("/api/students/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/students/{id}", id)).andExpect(status().isNotFound());

        MvcResult imported = mvc.perform(get("/api/students").param("query", importedRegister))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andReturn();
        long importedId = mapper.readTree(imported.getResponse().getContentAsByteArray()).get(0).get("id").asLong();
        mvc.perform(delete("/api/students/{id}", importedId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/fields/{key}", fieldKey)).andExpect(status().isNoContent());

        String legacyRegister = "LEGACY-" + suffix;
        MockMultipartFile legacyFile = new MockMultipartFile("file", "students.xls", "application/vnd.ms-excel",
            legacyWorkbook(legacyRegister));
        mvc.perform(multipart("/api/import").file(legacyFile))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted", is(1)))
            .andExpect(jsonPath("$.updated", is(0)));
        MvcResult legacy = mvc.perform(get("/api/students").param("query", legacyRegister))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()", is(1)))
            .andReturn();
        long legacyId = mapper.readTree(legacy.getResponse().getContentAsByteArray()).get(0).get("id").asLong();
        mvc.perform(get("/api/students/{id}", legacyId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date_of_birth", is("04/05/2004")));
        mvc.perform(delete("/api/students/{id}", legacyId)).andExpect(status().isNoContent());

        mvc.perform(post("/api/students")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Collections.singletonMap("first_name", "Missing register"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/students")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("Request body must be valid JSON.")));
    }

    private byte[] importWorkbook(String existingRegister, String newRegister) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Register No");
            header.createCell(1).setCellValue("First Name");
            header.createCell(2).setCellValue("Section");
            header.createCell(3).setCellValue("Day Scholar / Hosteller");
            header.createCell(4).setCellValue("Scholarship Status");
            Row refresh = sheet.createRow(1);
            refresh.createCell(0).setCellValue(existingRegister.toLowerCase(Locale.ROOT));
            refresh.createCell(1).setCellValue("From import");
            refresh.createCell(2).setCellValue("D");
            refresh.createCell(3).setCellValue("Day Scholar");
            refresh.createCell(4).setCellValue("No");
            Row insert = sheet.createRow(2);
            insert.createCell(0).setCellValue(newRegister);
            insert.createCell(1).setCellValue("Imported");
            insert.createCell(2).setCellValue("D");
            insert.createCell(3).setCellValue("Day Scholar");
            insert.createCell(4).setCellValue("Yes");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] legacyWorkbook(String register) throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Register Number");
            header.createCell(1).setCellValue("First Name");
            header.createCell(2).setCellValue("Date of Birth");
            Row student = sheet.createRow(1);
            student.createCell(0).setCellValue(register);
            student.createCell(1).setCellValue("Legacy");
            Calendar dob = Calendar.getInstance();
            dob.clear();
            dob.set(2004, Calendar.MAY, 4, 12, 0, 0);
            org.apache.poi.ss.usermodel.Cell date = student.createCell(2);
            date.setCellValue(dob.getTime());
            org.apache.poi.ss.usermodel.CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("m/d/yy"));
            date.setCellStyle(dateStyle);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
