package com.srm.studenthub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private static final Set<String> SENSITIVE_FIELDS = new LinkedHashSet<>(Arrays.asList(
        "student_mobile", "aadhaar_number", "abc_id", "father_name", "father_mobile", "father_occupation",
        "mother_name", "mother_mobile", "mother_occupation", "single_parent_status", "guardian_name",
        "single_child_status", "family_income", "communication_address"
    ));
    private static final Map<String, String> STUDENT_LABELS = studentLabels();
    private final JdbcTemplate jdbc;
    private final AcademicService academic;

    public ReportService(JdbcTemplate jdbc, AcademicService academic) {
        this.jdbc = jdbc;
        this.academic = academic;
    }

    public Map<String, Object> preview(String category, Map<String, Object> request) {
        ReportData data = build(category, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", data.title);
        result.put("category", data.category);
        result.put("type", data.type);
        result.put("generatedAt", data.generatedAt);
        result.put("filters", data.filterSummary);
        result.put("recordCount", data.rows.size());
        result.put("columns", data.columns);
        result.put("rows", data.rows.subList(0, Math.min(100, data.rows.size())));
        result.put("truncated", data.rows.size() > 100);
        return result;
    }

    public ExportFile export(String category, Map<String, Object> request, String format) {
        ReportData data = build(category, request);
        return export(data, format);
    }

    public ExportFile exportRows(String title, String filters, List<String> columns,
                                 List<Map<String, Object>> rows, String format) {
        ReportData data = new ReportData("smart-query", "query", title,
            LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE), filters, columns, rows);
        return export(data, format);
    }

    private ExportFile export(ReportData data, String format) {
        String normalized = format == null ? "xlsx" : format.toLowerCase(Locale.ROOT);
        try {
            if ("xlsx".equals(normalized)) {
                return new ExportFile(excel(data), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    fileName(data, "xlsx"));
            }
            if ("csv".equals(normalized)) {
                return new ExportFile(csv(data), "text/csv; charset=UTF-8", fileName(data, "csv"));
            }
            if ("pdf".equals(normalized)) {
                return new ExportFile(pdf(data), "application/pdf", fileName(data, "pdf"));
            }
            throw new IllegalArgumentException("Export format must be xlsx, csv, or pdf.");
        } catch (IOException exception) {
            throw new IllegalStateException("The report could not be generated.", exception);
        }
    }

    private ReportData build(String category, Map<String, Object> request) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String type = value(request.get("type"), "directory");
        Map<String, String> filters = stringFilters(request);
        String title;
        List<String> columns;
        List<Map<String, Object>> rows;
        if ("students".equals(normalized)) {
            if (!Arrays.asList("directory", "scholarship", "hostellers", "day-scholars",
                    "parent-contacts", "placement-interest").contains(type)) {
                throw new IllegalArgumentException("Choose a supported student report type.");
            }
            title = studentTitle(type);
            rows = studentRows(type, request, filters);
            columns = studentColumns(type, request);
        } else if ("attendance".equals(normalized)) {
            if (!Arrays.asList("daily", "subject", "student-history", "shortage",
                    "monthly-summary", "section-summary").contains(type)) {
                throw new IllegalArgumentException("Choose a supported attendance report type.");
            }
            title = attendanceTitle(type);
            rows = attendanceRows(type, filters);
            columns = Arrays.asList("Student", "Register Number", "Section", "Subject", "Date", "Period",
                "Attendance %", "Present", "Absent", "Late", "On Duty", "Leave", "Status", "Status Indicator", "Remarks");
            if ("monthly-summary".equals(type)) {
                columns = new ArrayList<>(columns);
                columns.add(3, "Month");
            }
        } else if ("marks".equals(normalized)) {
            if (!Arrays.asList("assessment", "subject", "student-progress", "class-performance",
                    "below-pass", "rank-list", "semester-summary").contains(type)) {
                throw new IllegalArgumentException("Choose a supported marks report type.");
            }
            title = marksTitle(type);
            rows = marksRows(type, filters);
            columns = Arrays.asList("Student", "Register Number", "Section", "Subject", "Assessment",
                "Assessment Type", "Semester", "Marks Obtained", "Maximum Marks", "Percentage",
                "Grade", "Rank", "Status", "Remarks");
        } else {
            throw new IllegalArgumentException("Report category must be students, attendance, or marks.");
        }
        String generated = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String filterSummary = filterSummary(filters);
        return new ReportData(normalized, type, title, generated, filterSummary, columns, rows);
    }

    private List<Map<String, Object>> studentRows(String type, Map<String, Object> request, Map<String, String> filters) {
        boolean sensitive = Boolean.TRUE.equals(request.get("includeSensitive"))
            && Boolean.TRUE.equals(request.get("sensitiveConfirmed"));
        if ("parent-contacts".equals(type) && !sensitive) {
            throw new IllegalArgumentException("Confirm the sensitive-data warning to export parent contact details.");
        }
        List<String> keys = studentKeys(type, sensitive);
        StringBuilder sql = new StringBuilder("SELECT id, " + String.join(", ", keys) + " FROM students WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendStudentFilters(sql, args, filters);
        if ("scholarship".equals(type)) {
            sql.append(" AND LOWER(COALESCE(scholarship_status,'')) IN ('yes','y','true')");
        } else if ("hostellers".equals(type)) {
            sql.append(" AND LOWER(COALESCE(residency,'')) LIKE '%hostel%'");
        } else if ("day-scholars".equals(type)) {
            sql.append(" AND LOWER(COALESCE(residency,'')) NOT LIKE '%hostel%' AND TRIM(COALESCE(residency,'')) <> ''");
        } else if ("placement-interest".equals(type)) {
            sql.append(" AND (LOWER(COALESCE(plan_after_degree,'')) LIKE '%placement%' OR TRIM(COALESCE(dream_company,'')) <> '')");
        }
        sql.append(" ORDER BY register_no");
        List<Map<String, Object>> databaseRows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> databaseRow : databaseRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : keys) {
                row.put(STUDENT_LABELS.getOrDefault(key, key), databaseRow.get(key));
            }
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> attendanceRows(String type, Map<String, String> filters) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDateSectionSubjectStudent(where, args, filters, "se");
        if (hasText(filters.get("status"))) {
            where.append(" AND ar.status = ?");
            args.add(filters.get("status"));
        }
        boolean summaryType = Arrays.asList("shortage", "monthly-summary", "section-summary").contains(type);
        boolean monthly = "monthly-summary".equals(type);
        String sql;
        if (summaryType) {
            sql = "SELECT s.id AS student_id, s.first_name, s.last_name, s.register_no, s.section, "
                + (monthly ? "EXTRACT(YEAR FROM se.attendance_date) AS report_year, EXTRACT(MONTH FROM se.attendance_date) AS report_month, " : "")
                + "COUNT(ar.id) AS total_count, "
                + "SUM(CASE WHEN ar.status='Present' THEN 1 ELSE 0 END) AS present_count, "
                + "SUM(CASE WHEN ar.status='Absent' THEN 1 ELSE 0 END) AS absent_count, "
                + "SUM(CASE WHEN ar.status='Late' THEN 1 ELSE 0 END) AS late_count, "
                + "SUM(CASE WHEN ar.status='On Duty' THEN 1 ELSE 0 END) AS duty_count, "
                + "SUM(CASE WHEN ar.status='Leave' THEN 1 ELSE 0 END) AS leave_count, "
                + "SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END) AS attended_count "
                + "FROM attendance_records ar JOIN attendance_sessions se ON se.id=ar.session_id "
                + "JOIN students s ON s.id=ar.student_id" + where
                + " GROUP BY s.id, s.first_name, s.last_name, s.register_no, s.section"
                + (monthly ? ", EXTRACT(YEAR FROM se.attendance_date), EXTRACT(MONTH FROM se.attendance_date)" : "")
                + ("shortage".equals(type) ? " HAVING COUNT(ar.id)>0 AND SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END)*100.0/COUNT(ar.id)<75" : "")
                + (monthly ? " ORDER BY report_year, report_month, s.section, s.register_no" : " ORDER BY s.section, s.register_no");
        } else {
            sql = "SELECT s.id AS student_id, s.first_name, s.last_name, s.register_no, s.section, "
                + "se.subject_name, se.attendance_date, se.hour_or_period, ar.status, ar.remarks "
                + "FROM attendance_records ar JOIN attendance_sessions se ON se.id=ar.session_id "
                + "JOIN students s ON s.id=ar.student_id" + where
                + " ORDER BY se.attendance_date DESC, se.subject_name, s.register_no";
        }
        List<Map<String, Object>> databaseRows = jdbc.queryForList(sql, args.toArray());
        Map<String, Map<String, Object>> detailedTotals = summaryType ? Collections.emptyMap() : attendanceDetailTotals(filters);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> databaseRow : databaseRows) {
            Map<String, Object> row = attendanceRow(databaseRow, summaryType);
            if (summaryType) {
                int count = integer(databaseRow.get("total_count"));
                int attended = integer(databaseRow.get("attended_count"));
                double pct = count == 0 ? 0 : attended * 100.0 / count;
                row.put("Attendance %", round(pct));
                row.put("Status", attendanceIndicator(pct));
                row.put("Status Indicator", attendanceIndicator(pct));
                if (monthly) {
                    row.put("Month", String.format(Locale.ROOT, "%04d-%02d",
                        integer(databaseRow.get("report_year")), integer(databaseRow.get("report_month"))));
                }
            } else {
                Map<String, Object> totals = detailedTotals.get(String.valueOf(databaseRow.get("student_id")));
                int count = totals == null ? 0 : integer(totals.get("total_count"));
                int attended = totals == null ? 0 : integer(totals.get("attended_count"));
                double pct = count == 0 ? 0 : attended * 100.0 / count;
                row.put("Subject", databaseRow.get("subject_name"));
                row.put("Date", databaseRow.get("attendance_date"));
                row.put("Period", databaseRow.get("hour_or_period"));
                row.put("Status", databaseRow.get("status"));
                row.put("Remarks", databaseRow.get("remarks"));
                row.put("Attendance %", count == 0 ? 0 : round(pct));
                row.put("Status Indicator", count == 0 ? "No records" : attendanceIndicator(pct));
                row.put("Present", totals == null ? 0 : totals.get("present_count"));
                row.put("Absent", totals == null ? 0 : totals.get("absent_count"));
                row.put("Late", totals == null ? 0 : totals.get("late_count"));
                row.put("On Duty", totals == null ? 0 : totals.get("duty_count"));
                row.put("Leave", totals == null ? 0 : totals.get("leave_count"));
            }
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> marksRows(String type, Map<String, String> filters) {
        List<Map<String, Object>> records = academic.marks(filters);
        Map<String, Object> performance = academic.marksPerformance(filters);
        Map<String, Map<String, Object>> ranks = new HashMap<>();
        for (Map<String, Object> student : castList(performance.get("students"))) {
            ranks.put(String.valueOf(student.get("studentId")), student);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> studentPerformance = ranks.get(String.valueOf(record.get("student_id")));
            if ("below-pass".equals(type) && (record.get("percentage") == null || number(record.get("percentage")) >= 40)) {
                continue;
            }
            if ("rank-list".equals(type) && (Boolean.TRUE.equals(record.get("missing")) || studentPerformance == null)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Student", joinName(record.get("first_name"), record.get("last_name")));
            row.put("Register Number", record.get("register_no"));
            row.put("Section", record.get("section"));
            row.put("Subject", record.get("subject_name"));
            row.put("Assessment", record.get("assessment_name"));
            row.put("Assessment Type", record.get("assessment_type"));
            row.put("Semester", record.get("semester"));
            row.put("Marks Obtained", record.get("marks_obtained"));
            row.put("Maximum Marks", record.get("max_marks"));
            row.put("Percentage", record.get("percentage"));
            row.put("Grade", record.get("grade"));
            row.put("Rank", studentPerformance == null ? "" : studentPerformance.get("rank"));
            row.put("Status", Boolean.TRUE.equals(record.get("missing")) ? "Missing"
                : Boolean.TRUE.equals(record.get("pass")) ? "Pass" : "Fail");
            row.put("Remarks", record.get("remarks"));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> attendanceRow(Map<String, Object> row, boolean summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Student", joinName(row.get("first_name"), row.get("last_name")));
        result.put("Register Number", row.get("register_no"));
        result.put("Section", row.get("section"));
        result.put("Subject", "");
        result.put("Date", "");
        result.put("Period", "");
        result.put("Attendance %", "");
        result.put("Present", summary ? row.get("present_count") : "");
        result.put("Absent", summary ? row.get("absent_count") : "");
        result.put("Late", summary ? row.get("late_count") : "");
        result.put("On Duty", summary ? row.get("duty_count") : "");
        result.put("Leave", summary ? row.get("leave_count") : "");
        result.put("Status", "");
        result.put("Status Indicator", "");
        result.put("Remarks", "");
        return result;
    }

    private Map<String, Map<String, Object>> attendanceDetailTotals(Map<String, String> filters) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendDateSectionSubjectStudent(where, args, filters, "se");
        String sql = "SELECT s.id AS student_id, COUNT(ar.id) AS total_count, "
            + "SUM(CASE WHEN ar.status='Present' THEN 1 ELSE 0 END) AS present_count, "
            + "SUM(CASE WHEN ar.status='Absent' THEN 1 ELSE 0 END) AS absent_count, "
            + "SUM(CASE WHEN ar.status='Late' THEN 1 ELSE 0 END) AS late_count, "
            + "SUM(CASE WHEN ar.status='On Duty' THEN 1 ELSE 0 END) AS duty_count, "
            + "SUM(CASE WHEN ar.status='Leave' THEN 1 ELSE 0 END) AS leave_count, "
            + "SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END) AS attended_count "
            + "FROM attendance_records ar JOIN attendance_sessions se ON se.id=ar.session_id "
            + "JOIN students s ON s.id=ar.student_id" + where
            + " GROUP BY s.id";
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            result.put(String.valueOf(row.get("student_id")), row);
        }
        return result;
    }

    private static List<String> studentColumns(String type, Map<String, Object> request) {
        boolean sensitive = Boolean.TRUE.equals(request.get("includeSensitive"))
            && Boolean.TRUE.equals(request.get("sensitiveConfirmed"));
        List<String> result = new ArrayList<>();
        for (String key : studentKeys(type, sensitive)) {
            result.add(STUDENT_LABELS.getOrDefault(key, key));
        }
        return result;
    }

    private static List<String> studentKeys(String type, boolean sensitive) {
        List<String> keys = new ArrayList<>(Arrays.asList(
            "register_no", "admission_no", "first_name", "last_name", "academic_year", "section", "gender",
            "date_of_birth", "official_email", "mode_of_admission", "residency", "bus_number", "boarding_point",
            "hostel_block", "room_number", "scholarship_status", "scholarship_name", "scholarship_amount",
            "tenth_marks", "tenth_board", "tenth_state", "twelfth_percentage", "twelfth_total", "twelfth_cutoff",
            "twelfth_board", "twelfth_school_city", "twelfth_study_state", "plan_after_degree", "dream_company"
        ));
        if (sensitive) {
            List<String> privateKeys = new ArrayList<>(SENSITIVE_FIELDS);
            keys.addAll(privateKeys);
        }
        if ("parent-contacts".equals(type)) {
            keys.removeAll(keys);
            keys.addAll(Arrays.asList("register_no", "first_name", "last_name", "section", "father_name", "father_mobile",
                "mother_name", "mother_mobile", "guardian_name", "communication_address"));
        }
        if ("placement-interest".equals(type)) {
            keys.removeAll(keys);
            keys.addAll(Arrays.asList("register_no", "first_name", "last_name", "section", "official_email", "plan_after_degree", "dream_company"));
        }
        return keys;
    }

    private static void appendStudentFilters(StringBuilder sql, List<Object> args, Map<String, String> filters) {
        if (hasText(filters.get("section"))) {
            sql.append(" AND LOWER(COALESCE(section,''))=LOWER(?)");
            args.add(filters.get("section").trim());
        }
        if (hasText(filters.get("studentId"))) {
            sql.append(" AND id=?");
            args.add(filters.get("studentId"));
        }
        if (hasText(filters.get("academicYear"))) {
            sql.append(" AND LOWER(COALESCE(academic_year,''))=LOWER(?)");
            args.add(filters.get("academicYear"));
        }
        if (hasText(filters.get("query"))) {
            String pattern = "%" + filters.get("query").trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" AND (LOWER(register_no) LIKE ? OR LOWER(first_name) LIKE ? OR LOWER(last_name) LIKE ? OR LOWER(admission_no) LIKE ? OR LOWER(official_email) LIKE ?)");
            for (int i = 0; i < 5; i++) args.add(pattern);
        }
    }

    private static void appendDateSectionSubjectStudent(StringBuilder sql, List<Object> args,
                                                        Map<String, String> filters, String sessionAlias) {
        if (hasText(filters.get("startDate"))) {
            sql.append(" AND ").append(sessionAlias).append(".attendance_date>=?");
            args.add(java.sql.Date.valueOf(filters.get("startDate")));
        }
        if (hasText(filters.get("endDate"))) {
            sql.append(" AND ").append(sessionAlias).append(".attendance_date<=?");
            args.add(java.sql.Date.valueOf(filters.get("endDate")));
        }
        if (hasText(filters.get("section"))) {
            sql.append(" AND LOWER(").append(sessionAlias).append(".section)=LOWER(?)");
            args.add(filters.get("section"));
        }
        if (hasText(filters.get("subject"))) {
            sql.append(" AND LOWER(").append(sessionAlias).append(".subject_name) LIKE ?");
            args.add("%" + filters.get("subject").toLowerCase(Locale.ROOT) + "%");
        }
        if (hasText(filters.get("studentId"))) {
            sql.append(" AND s.id=?");
            args.add(filters.get("studentId"));
        }
    }

    private static String studentTitle(String type) {
        if ("scholarship".equals(type)) return "Scholarship Student List";
        if ("hostellers".equals(type)) return "Hosteller List";
        if ("day-scholars".equals(type)) return "Day Scholar List";
        if ("parent-contacts".equals(type)) return "Parent Contact List";
        if ("placement-interest".equals(type)) return "Placement Interest List";
        return "Student Directory";
    }

    private static String attendanceTitle(String type) {
        if ("shortage".equals(type)) return "Attendance Shortage List";
        if ("monthly-summary".equals(type)) return "Monthly Attendance Summary";
        if ("section-summary".equals(type)) return "Section Attendance Report";
        if ("student-history".equals(type)) return "Student Attendance History";
        if ("subject".equals(type)) return "Subject-wise Attendance Report";
        return "Daily Attendance Sheet";
    }

    private static String marksTitle(String type) {
        if ("below-pass".equals(type)) return "Students Below Pass Mark";
        if ("rank-list".equals(type)) return "Section Rank List";
        if ("student-progress".equals(type)) return "Student Progress Report";
        if ("class-performance".equals(type)) return "Class Performance Report";
        if ("semester-summary".equals(type)) return "Semester Marks Summary";
        if ("subject".equals(type)) return "Subject-wise Marks Report";
        return "Assessment Marks Sheet";
    }

    private static String filterSummary(Map<String, String> filters) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            if (hasText(filter.getValue())) {
                values.add(filter.getKey() + ": " + filter.getValue());
            }
        }
        return values.isEmpty() ? "No filters" : String.join(" · ", values);
    }

    private static byte[] csv(ReportData data) {
        StringBuilder output = new StringBuilder("\uFEFF");
        output.append("# ").append(csvValue(data.title)).append("\r\n");
        output.append("# Generated: ").append(data.generatedAt).append("\r\n");
        output.append("# Filters: ").append(csvValue(data.filterSummary)).append("\r\n");
        output.append("# Total records: ").append(data.rows.size()).append("\r\n");
        appendCsvRow(output, data.columns);
        for (Map<String, Object> row : data.rows) {
            List<Object> values = new ArrayList<>();
            for (String column : data.columns) values.add(row.get(column));
            appendCsvRow(output, values);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsvRow(StringBuilder output, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) output.append(',');
            output.append(csvValue(values.get(i)));
        }
        output.append("\r\n");
    }

    private static String csvValue(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static byte[] excel(ReportData data) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Report");
            CellStyle titleStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 15);
            titleStyle.setFont(titleFont);
            int rowIndex = 0;
            Row title = sheet.createRow(rowIndex++);
            title.createCell(0).setCellValue(data.title);
            title.getCell(0).setCellStyle(titleStyle);
            sheet.createRow(rowIndex++).createCell(0).setCellValue("Generated: " + data.generatedAt);
            sheet.createRow(rowIndex++).createCell(0).setCellValue("Filters: " + data.filterSummary);
            sheet.createRow(rowIndex++).createCell(0).setCellValue("Total records: " + data.rows.size());
            Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < data.columns.size(); i++) {
                header.createCell(i).setCellValue(data.columns.get(i));
                header.getCell(i).setCellStyle(titleStyle);
            }
            for (Map<String, Object> values : data.rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < data.columns.size(); i++) {
                    Object value = values.get(data.columns.get(i));
                    row.createCell(i).setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            for (int i = 0; i < data.columns.size(); i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf(ReportData data) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int rowIndex = 0;
            while (rowIndex < Math.max(data.rows.size(), 1)) {
                PDPage page = new PDPage(new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    float y = page.getMediaBox().getHeight() - 34;
                    text(stream, 30, y, 14, data.title);
                    y -= 18;
                    text(stream, 30, y, 8, "Generated: " + data.generatedAt + " | Filters: " + truncate(data.filterSummary, 100));
                    y -= 12;
                    text(stream, 30, y, 8, "Total records: " + data.rows.size());
                    y -= 18;
                    text(stream, 30, y, 7, truncate(String.join(" | ", data.columns), 180));
                    y -= 13;
                    int pageEnd = Math.min(rowIndex + 40, data.rows.size());
                    if (data.rows.isEmpty()) {
                        text(stream, 30, y, 8, "No records matched the selected filters.");
                    }
                    for (; rowIndex < pageEnd; rowIndex++) {
                        List<String> values = new ArrayList<>();
                        for (String column : data.columns) values.add(truncate(String.valueOf(data.rows.get(rowIndex).getOrDefault(column, "")), 35));
                        text(stream, 30, y, 6, truncate(String.join(" | ", values), 210));
                        y -= 11;
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void text(PDPageContentStream stream, float x, float y, int size, String value) throws IOException {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, size);
        stream.newLineAtOffset(x, y);
        stream.showText(pdfSafe(value));
        stream.endText();
    }

    private static String pdfSafe(String value) {
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static String fileName(ReportData data, String extension) {
        String slug = data.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug + "-" + data.generatedAt + "." + extension;
    }

    private static Map<String, String> stringFilters(Map<String, Object> request) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String key : Arrays.asList("startDate", "endDate", "date", "section", "subject", "studentId", "status",
            "query", "subjectId", "assessmentId", "semester", "academicYear", "assessmentType", "missingOnly")) {
            if (request.containsKey(key) && request.get(key) != null) {
                filters.put(key, String.valueOf(request.get(key)));
            }
        }
        return filters;
    }

    private static Map<String, String> studentLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("register_no", "Register Number");
        labels.put("admission_no", "Admission Number");
        labels.put("first_name", "First Name");
        labels.put("last_name", "Last Name");
        labels.put("academic_year", "Academic Year");
        labels.put("section", "Section");
        labels.put("gender", "Gender");
        labels.put("date_of_birth", "Date of Birth");
        labels.put("student_mobile", "Student Mobile");
        labels.put("official_email", "Official Email");
        labels.put("aadhaar_number", "Aadhaar Number");
        labels.put("abc_id", "ABC ID");
        labels.put("mode_of_admission", "Mode of Admission");
        labels.put("residency", "Residence");
        labels.put("bus_number", "Bus Number");
        labels.put("boarding_point", "Boarding Point");
        labels.put("hostel_block", "Hostel Block");
        labels.put("room_number", "Room Number");
        labels.put("scholarship_status", "Scholarship Status");
        labels.put("scholarship_name", "Scholarship Name");
        labels.put("scholarship_amount", "Scholarship Amount");
        labels.put("tenth_marks", "10th Marks");
        labels.put("tenth_board", "10th Board");
        labels.put("tenth_state", "10th State");
        labels.put("twelfth_percentage", "12th Percentage");
        labels.put("twelfth_total", "12th Total");
        labels.put("twelfth_cutoff", "12th Cutoff");
        labels.put("twelfth_board", "12th Board");
        labels.put("twelfth_school_city", "12th School and City");
        labels.put("twelfth_study_state", "State of 12th Study");
        labels.put("father_name", "Father Name");
        labels.put("father_mobile", "Father Mobile");
        labels.put("father_occupation", "Father Occupation");
        labels.put("mother_name", "Mother Name");
        labels.put("mother_mobile", "Mother Mobile");
        labels.put("mother_occupation", "Mother Occupation");
        labels.put("single_parent_status", "Single Parent Status");
        labels.put("guardian_name", "Parent / Guardian Name");
        labels.put("single_child_status", "Single Child Status");
        labels.put("family_income", "Family Income");
        labels.put("communication_address", "Communication Address");
        labels.put("plan_after_degree", "Plan After Degree");
        labels.put("dream_company", "Dream Company");
        return labels;
    }

    private static String value(Object raw, String fallback) {
        return raw == null || String.valueOf(raw).trim().isEmpty() ? fallback : String.valueOf(raw).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String joinName(Object first, Object last) {
        return String.valueOf(first == null ? "" : first) + (last == null || String.valueOf(last).isEmpty() ? "" : " " + last);
    }

    private static int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String attendanceIndicator(double percentage) {
        return percentage < 75 ? "Critical shortage" : percentage < 85 ? "Needs attention" : "Good attendance";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object rows) {
        return (List<Map<String, Object>>) rows;
    }

    public static final class ExportFile {
        public final byte[] bytes;
        public final String contentType;
        public final String fileName;

        private ExportFile(byte[] bytes, String contentType, String fileName) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.fileName = fileName;
        }
    }

    private static final class ReportData {
        private final String category;
        private final String type;
        private final String title;
        private final String generatedAt;
        private final String filterSummary;
        private final List<String> columns;
        private final List<Map<String, Object>> rows;

        private ReportData(String category, String type, String title, String generatedAt, String filterSummary,
                           List<String> columns, List<Map<String, Object>> rows) {
            this.category = category;
            this.type = type;
            this.title = title;
            this.generatedAt = generatedAt;
            this.filterSummary = filterSummary;
            this.columns = columns;
            this.rows = rows;
        }
    }
}
