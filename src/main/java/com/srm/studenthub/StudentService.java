package com.srm.studenthub;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudentService {
    private static final Map<String, List<String>> HEADER_ALIASES = createAliases();
    private final StudentRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StudentService(StudentRepository repository, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> search(String query, String section, String residency, String scholarship) {
        return repository.search(query, section, residency, scholarship);
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> student = repository.findById(id);
        if (student == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found.");
        }
        student.put("customFields", repository.customValues(id));
        return student;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        Map<String, String> student = standardValues(payload);
        validateRequired(student);
        long id = repository.insert(student);
        saveCustomValues(id, customValues(payload));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> payload) {
        if (repository.findById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found.");
        }
        Map<String, String> student = standardValues(payload);
        validateRequired(student);
        repository.update(id, student);
        saveCustomValues(id, customValues(payload));
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        if (repository.delete(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found.");
        }
    }

    public Map<String, Object> metadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        int total = repository.count();
        Integer hostellers = jdbc.queryForObject(
            "SELECT COUNT(*) FROM students WHERE LOWER(COALESCE(residency, '')) LIKE '%hostel%'", Integer.class);
        Integer scholarship = jdbc.queryForObject(
            "SELECT COUNT(*) FROM students WHERE LOWER(COALESCE(scholarship_status, '')) IN ('yes', 'y', 'true')",
            Integer.class);
        List<String> sections = repository.sections();
        values.put("totalStudents", total);
        values.put("hostellers", hostellers == null ? 0 : hostellers);
        values.put("scholarshipStudents", scholarship == null ? 0 : scholarship);
        values.put("sectionCount", sections.size());
        values.put("sections", sections);
        values.put("fields", StudentFieldCatalog.metadata());
        return values;
    }

    public List<Map<String, Object>> customFields() {
        return repository.customFields();
    }

    @Transactional
    public Map<String, Object> addCustomField(Map<String, Object> payload) {
        String label = text(payload.get("label")).trim();
        String type = text(payload.get("type")).trim().toLowerCase(Locale.ROOT);
        if (label.isEmpty() || label.length() > 200) {
            throw new IllegalArgumentException("Enter a field label (up to 200 characters).");
        }
        if (!Arrays.asList("text", "number", "date", "option").contains(type)) {
            throw new IllegalArgumentException("Field type must be text, number, date, or option.");
        }
        String key = slug(label);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("The field label must contain letters or numbers.");
        }
        if (StudentFieldCatalog.get(key) != null) {
            throw new IllegalArgumentException("That label conflicts with a standard student field.");
        }
        try {
            repository.insertCustomField(key, label, type);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A custom field with that label already exists.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("label", label);
        result.put("type", type);
        return result;
    }

    @Transactional
    public void deleteCustomField(String key) {
        boolean found = false;
        for (Map<String, Object> field : repository.customFields()) {
            if (key.equals(field.get("key"))) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Custom field not found.");
        }
        repository.deleteCustomField(key);
    }

    @Transactional
    public Map<String, Object> importWorkbook(byte[] content, String filename) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!(lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls"))) {
            throw new IllegalArgumentException("Choose an Excel workbook with an .xlsx or .xls extension.");
        }
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        try (Workbook workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("The workbook does not contain a worksheet.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("The first worksheet is empty.");
            }
            DataFormatter formatter = new DataFormatter();
            org.apache.poi.ss.usermodel.FormulaEvaluator evaluator =
                workbook.getCreationHelper().createFormulaEvaluator();
            Map<Integer, String> columns = mapHeaders(headerRow, formatter, evaluator);
            if (!columns.containsValue("register_no")) {
                throw new IllegalArgumentException("The first worksheet must include a Register Number column.");
            }

            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isEmpty(row, formatter, evaluator)) {
                    continue;
                }
                Map<String, String> imported = new LinkedHashMap<>();
                for (String key : StudentFieldCatalog.keys()) {
                    imported.put(key, "");
                }
                for (Map.Entry<Integer, String> column : columns.entrySet()) {
                    Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cellValue(cell, column.getValue(), formatter, evaluator);
                    if (!value.isEmpty()) {
                        imported.put(column.getValue(), value);
                    }
                }
                parseResidenceDetails(row, columns, imported, formatter, evaluator);
                String registerNo = imported.get("register_no").trim().toUpperCase(Locale.ROOT);
                if (registerNo.isEmpty() || imported.get("first_name").trim().isEmpty()) {
                    skipped++;
                    continue;
                }
                imported.put("register_no", registerNo);
                Map<String, Object> existing = repository.findByRegisterNo(registerNo);
                Map<String, String> merged = imported;
                if (existing != null) {
                    merged = new LinkedHashMap<>();
                    for (String key : StudentFieldCatalog.keys()) {
                        String newValue = imported.get(key);
                        Object oldValue = existing.get(key);
                        merged.put(key, newValue == null || newValue.trim().isEmpty()
                            ? (oldValue == null ? "" : String.valueOf(oldValue)) : newValue);
                    }
                    repository.update(((Number) existing.get("id")).longValue(), merged);
                    updated++;
                } else {
                    repository.insert(imported);
                    inserted++;
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("The Excel workbook could not be read. Check that the file is valid.");
        } catch (org.apache.poi.EncryptedDocumentException exception) {
            throw new IllegalArgumentException("Password-protected workbooks cannot be imported.");
        } catch (org.apache.poi.ooxml.POIXMLException exception) {
            throw new IllegalArgumentException("The selected file is not a valid Excel workbook.");
        }
        int total = inserted + updated;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("importedOrRefreshed", total);
        result.put("message", total + " student records imported or refreshed.");
        return result;
    }

    @Transactional
    public void seedIfEmpty() throws IOException {
        if (repository.count() > 0) {
            return;
        }
        ClassPathResource seed = new ClassPathResource("seed/students.json");
        if (!seed.exists()) {
            return;
        }
        List<Map<String, Object>> records;
        try (java.io.InputStream input = seed.getInputStream()) {
            records = objectMapper.readValue(input, new TypeReference<List<Map<String, Object>>>() { });
        }
        for (Map<String, Object> record : records) {
            Map<String, String> values = standardValues(record);
            if (!values.get("register_no").trim().isEmpty() && !values.get("first_name").trim().isEmpty()) {
                repository.insert(values);
            }
        }
    }

    private void saveCustomValues(long studentId, Map<String, String> values) {
        Map<String, Object> customFields = new HashMap<>();
        for (Map<String, Object> field : repository.customFields()) {
            customFields.put(String.valueOf(field.get("key")), field);
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!customFields.containsKey(value.getKey())) {
                throw new IllegalArgumentException("Unknown custom field: " + value.getKey());
            }
            repository.setCustomValue(studentId, value.getKey(), value.getValue());
        }
    }

    private Map<String, String> standardValues(Map<String, Object> payload) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : StudentFieldCatalog.keys()) {
            result.put(key, "");
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if ("customFields".equals(entry.getKey()) || "id".equals(entry.getKey())
                || "created_at".equals(entry.getKey()) || "updated_at".equals(entry.getKey())) {
                continue;
            }
            if (!StudentFieldCatalog.keys().contains(entry.getKey())) {
                throw new IllegalArgumentException("Unsupported student field: " + entry.getKey());
            }
            String value = text(entry.getValue()).trim();
            result.put(entry.getKey(), "register_no".equals(entry.getKey()) ? value.toUpperCase(Locale.ROOT) : value);
        }
        return result;
    }

    private Map<String, String> customValues(Map<String, Object> payload) {
        Object raw = payload.get("customFields");
        if (raw == null) {
            return Collections.emptyMap();
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("customFields must be an object of field keys and values.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            values.put(String.valueOf(entry.getKey()), text(entry.getValue()).trim());
        }
        return values;
    }

    private static void validateRequired(Map<String, String> student) {
        if (student.get("register_no").trim().isEmpty()) {
            throw new IllegalArgumentException("Register Number is required.");
        }
        if (student.get("first_name").trim().isEmpty()) {
            throw new IllegalArgumentException("First Name is required.");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String slug(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static Map<Integer, String> mapHeaders(Row row, DataFormatter formatter,
                                                    org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        Map<Integer, String> result = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : HEADER_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                aliases.put(normalizeHeader(alias), entry.getKey());
            }
        }
        for (Cell cell : row) {
            String key = aliases.get(normalizeHeader(formatter.formatCellValue(cell, evaluator)));
            if (key != null) {
                result.put(cell.getColumnIndex(), key);
            }
        }
        return result;
    }

    private static String cellValue(Cell cell, String key, DataFormatter formatter,
                                    org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if ("date_of_birth".equals(key) && cell.getCellType() == CellType.NUMERIC
            && DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
            Date date = DateUtil.getJavaDate(cell.getNumericCellValue());
            return new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(date);
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private static boolean isEmpty(Row row, DataFormatter formatter,
                                   org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void parseResidenceDetails(Row row, Map<Integer, String> columns, Map<String, String> values,
                                              DataFormatter formatter,
                                              org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        for (Map.Entry<Integer, String> column : columns.entrySet()) {
            if (!"residence_details".equals(column.getValue())) {
                continue;
            }
            Cell cell = row.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) {
                return;
            }
            String detail = formatter.formatCellValue(cell, evaluator).trim();
            setMatch(values, "bus_number", detail, "(?i)bus\\s*(?:no\\.?|number)?\\s*[:#-]?\\s*([A-Za-z0-9-]+)");
            setMatch(values, "boarding_point", detail,
                "(?i)boarding\\s*point\\s*[:#-]?\\s*([^,;]+)");
            setMatch(values, "hostel_block", detail, "(?i)(?:hostel\\s*)?block\\s*(?:no\\.?|number)?\\s*[:#-]?\\s*([A-Za-z0-9-]+)");
            setMatch(values, "room_number", detail, "(?i)room\\s*(?:no\\.?|number)?\\s*[:#-]?\\s*([A-Za-z0-9-]+)");
            return;
        }
    }

    private static void setMatch(Map<String, String> values, String key, String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (matcher.find() && values.get(key).trim().isEmpty()) {
            values.put(key, matcher.group(1).trim());
        }
    }

    private static String normalizeHeader(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, List<String>> createAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        alias(aliases, "register_no", "Register Number", "Register No", "Register No.");
        alias(aliases, "admission_no", "Admission Number", "Admission No", "Admission No.",
            "Admission No. ( it's available in your email when you were admitted in college)");
        alias(aliases, "first_name", "First Name", "FirstName");
        alias(aliases, "last_name", "Last Name", "LastName", "Surname");
        alias(aliases, "academic_year", "Academic Year", "Year");
        alias(aliases, "section", "Section", "Class Section");
        alias(aliases, "gender", "Gender", "Sex");
        alias(aliases, "date_of_birth", "Date of Birth", "DOB", "Birth Date");
        alias(aliases, "student_mobile", "Student Mobile", "Student Phone", "Mobile Number");
        alias(aliases, "official_email", "Official Email", "Email", "Official Email (srmist.edu.in)");
        alias(aliases, "mode_of_admission", "Mode of Admission", "Mode of Admission(SRMJEE / Direct)");
        alias(aliases, "residency", "Day Scholar / Hosteller", "Dayscholar / Hosteller", "Residence", "Residency");
        alias(aliases, "residence_details", "If Dayscholar, Bus No. and Boarding Point. If Hosteller, Block No. and Room No.");
        alias(aliases, "scholarship_status", "Scholarship", "Scholarship Status");
        alias(aliases, "scholarship_name", "Scholarship Name");
        alias(aliases, "scholarship_amount", "Scholarship Amount");
        alias(aliases, "aadhaar_number", "Aadhaar", "Aadhaar Number", "Aadhar Number");
        alias(aliases, "abc_id", "ABC ID", "Academic Bank of Credits ID");
        alias(aliases, "tenth_marks", "10th Marks", "10th Mark");
        alias(aliases, "tenth_board", "10th Board");
        alias(aliases, "tenth_state", "10th State");
        alias(aliases, "twelfth_percentage", "12th Percentage", "12th %", "12th Percent");
        alias(aliases, "twelfth_total", "12th Total");
        alias(aliases, "twelfth_cutoff", "12th Cutoff");
        alias(aliases, "twelfth_board", "12th Board");
        alias(aliases, "twelfth_school_city", "12th School and City", "12th School name and City name");
        alias(aliases, "twelfth_study_state", "State of 12th Study", "State of 12th STD Studied");
        alias(aliases, "father_name", "Father Name", "Father's Name");
        alias(aliases, "father_mobile", "Father Mobile", "Father Phone");
        alias(aliases, "father_occupation", "Father Occupation", "Father's Occupation (Mention the position and address",
            "Father's Occupation (Mention position and address");
        alias(aliases, "mother_name", "Mother Name", "Mother's Name");
        alias(aliases, "mother_mobile", "Mother Mobile", "Mother Phone");
        alias(aliases, "mother_occupation", "Mother Occupation", "Mother's Occupation (Mention the position and address",
            "Mother's Occupation (Mention position and address");
        alias(aliases, "single_parent_status", "Single Parent", "Single Parent Status");
        alias(aliases, "guardian_name", "Parent / Guardian Name", "Single Parent is either father or mother");
        alias(aliases, "single_child_status", "Are you a Single Child?", "Single Child Status");
        alias(aliases, "family_income", "Family Income");
        alias(aliases, "communication_address", "Detailed address for Communication", "Communication Address");
        alias(aliases, "plan_after_degree", "What is your plan after completing your degree?", "Plan After Degree");
        alias(aliases, "dream_company", "If Placement, what is your dream company?", "Dream Company");
        return aliases;
    }

    private static void alias(Map<String, List<String>> aliases, String key, String... values) {
        aliases.put(key, new ArrayList<>(Arrays.asList(values)));
    }
}
