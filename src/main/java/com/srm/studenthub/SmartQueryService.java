package com.srm.studenthub;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SmartQueryService {
    private static final List<String> CERTIFICATE_STATUSES = Arrays.asList(
        "Completed", "In Progress", "Not Started", "Not Applicable");
    private static final List<String> SUGGESTIONS = Arrays.asList(
        "Students interested in TCS",
        "Scholarship students",
        "Hostellers",
        "Attendance below 75%",
        "Globalization Certificate completed",
        "Students interested in placement",
        "Students with low marks");
    private static final Pattern SECTION = Pattern.compile("\\bsection\\s+([a-z0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGISTER_NUMBER = Pattern.compile(
        "\\b(?:registration\\s*(?:number|no\\.?)|register\\s*(?:number|no\\.?)|reg\\.?\\s*no\\.?)\\s*(?:is\\s+|[:#]\\s*)?([a-z0-9]+(?:[-/][a-z0-9]+)*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTENDANCE_THRESHOLD = Pattern.compile(
        "\\b(below|under|less than|above|over|more than|at least|at most)\\s+(\\d{1,3}(?:\\.\\d+)?)\\s*%?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKS_THRESHOLD = ATTENDANCE_THRESHOLD;
    private static final Pattern CERTIFICATE_NAME = Pattern.compile(
        "\\b([a-z0-9][a-z0-9 &./-]{1,90}?(?:certificate|certification))\\b", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;

    public SmartQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> suggestions() {
        return SUGGESTIONS;
    }

    public List<Map<String, Object>> certificates() {
        return jdbc.query("SELECT id, certificate_name, issuing_organization, description, created_at "
                + "FROM certificates ORDER BY LOWER(certificate_name)",
            (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", rs.getLong("id"));
                result.put("certificateName", rs.getString("certificate_name"));
                result.put("issuingOrganization", rs.getString("issuing_organization"));
                result.put("description", rs.getString("description"));
                result.put("createdAt", rs.getTimestamp("created_at"));
                return result;
            });
    }

    @Transactional
    public Map<String, Object> saveCertificate(Long id, Map<String, Object> payload) {
        String name = requiredText(payload.get("certificateName"), "Certificate name");
        String organization = optionalText(payload.get("issuingOrganization"));
        String description = optionalText(payload.get("description"));
        if (id == null) {
            jdbc.update("INSERT INTO certificates (certificate_name, issuing_organization, description) VALUES (?, ?, ?)",
                name, organization, description);
        } else {
            int updated = jdbc.update("UPDATE certificates SET certificate_name = ?, issuing_organization = ?, description = ? WHERE id = ?",
                name, organization, description, id);
            if (updated == 0) {
                throw notFound("Certificate not found.");
            }
        }
        return certificateByName(name);
    }

    public void deleteCertificate(long id) {
        if (jdbc.update("DELETE FROM certificates WHERE id = ?", id) == 0) {
            throw notFound("Certificate not found.");
        }
    }

    public List<Map<String, Object>> studentCertificates(long studentId) {
        requireStudent(studentId);
        return jdbc.query("SELECT c.id AS certificate_id, c.certificate_name, c.issuing_organization, c.description, "
                + "sc.id AS student_certificate_id, sc.status, sc.completion_date, sc.certificate_number, sc.remarks "
                + "FROM certificates c LEFT JOIN student_certificates sc "
                + "ON sc.certificate_id = c.id AND sc.student_id = ? ORDER BY LOWER(c.certificate_name)",
            (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("certificateId", rs.getLong("certificate_id"));
                result.put("certificateName", rs.getString("certificate_name"));
                result.put("issuingOrganization", rs.getString("issuing_organization"));
                result.put("description", rs.getString("description"));
                result.put("studentCertificateId", rs.getObject("student_certificate_id"));
                result.put("status", rs.getString("status") == null ? "Not Started" : rs.getString("status"));
                Date date = rs.getDate("completion_date");
                result.put("completionDate", date == null ? null : date.toLocalDate().toString());
                result.put("certificateNumber", rs.getString("certificate_number"));
                result.put("remarks", rs.getString("remarks"));
                return result;
            }, studentId);
    }

    @Transactional
    public Map<String, Object> saveStudentCertificate(long studentId, long certificateId, Map<String, Object> payload) {
        requireStudent(studentId);
        if (!exists("SELECT COUNT(*) FROM certificates WHERE id = ?", certificateId)) {
            throw notFound("Certificate not found.");
        }
        String status = requiredText(payload.get("status"), "Certificate status");
        if (!CERTIFICATE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Choose a valid certificate status.");
        }
        LocalDate completionDate = optionalDate(payload.get("completionDate"));
        String number = optionalText(payload.get("certificateNumber"));
        String remarks = optionalText(payload.get("remarks"));
        int updated = jdbc.update("UPDATE student_certificates SET status = ?, completion_date = ?, "
                + "certificate_number = ?, remarks = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE student_id = ? AND certificate_id = ?",
            status, completionDate == null ? null : Date.valueOf(completionDate), number, remarks, studentId, certificateId);
        if (updated == 0) {
            jdbc.update("INSERT INTO student_certificates (student_id, certificate_id, status, completion_date, certificate_number, remarks) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                studentId, certificateId, status, completionDate == null ? null : Date.valueOf(completionDate), number, remarks);
        }
        for (Map<String, Object> certificate : studentCertificates(studentId)) {
            if (((Number) certificate.get("certificateId")).longValue() == certificateId) {
                return certificate;
            }
        }
        throw new IllegalStateException("The saved certificate record could not be loaded.");
    }

    public void deleteStudentCertificate(long studentId, long certificateId) {
        requireStudent(studentId);
        if (jdbc.update("DELETE FROM student_certificates WHERE student_id = ? AND certificate_id = ?",
                studentId, certificateId) == 0) {
            throw notFound("Student certificate record not found.");
        }
    }

    public Map<String, Object> execute(String query) {
        QueryIntent intent = parse(query);
        if (intent.category == null) {
            throw new IllegalArgumentException("I could not understand that query. Try “Students interested in TCS” or “Students below 75% attendance”.");
        }
        QueryResult result;
        switch (intent.category) {
            case "career":
                result = career(intent);
                break;
            case "certificate":
                result = certificate(intent);
                break;
            case "attendance":
                result = attendance(intent);
                break;
            case "marks":
                result = marks(intent);
                break;
            case "students":
                result = students(intent);
                break;
            default:
                throw new IllegalArgumentException("This query type is not supported.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", intent.originalQuery);
        response.put("category", intent.category);
        response.put("interpretedAs", intent.interpretedAs);
        response.put("summary", result.summary);
        response.put("count", result.rows.size());
        response.put("columns", result.columns);
        response.put("appliedFilters", result.filters);
        response.put("results", result.rows);
        return response;
    }

    public QueryIntent parse(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a question to search student records.");
        }
        if (query.length() > 300) {
            throw new IllegalArgumentException("Queries must be 300 characters or fewer.");
        }
        QueryIntent intent = new QueryIntent();
        intent.originalQuery = query.trim();
        String normalized = query.toLowerCase(Locale.ROOT);
        Matcher sectionMatch = SECTION.matcher(query);
        if (sectionMatch.find()) {
            intent.section = sectionMatch.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher registerNumberMatch = REGISTER_NUMBER.matcher(query);
        if (registerNumberMatch.find()) {
            intent.registerNumber = registerNumberMatch.group(1).trim();
        }
        intent.residenceType = normalized.matches("(?s).*\\b(hostellers?|hostel students?)\\b.*") ? "Hosteller"
            : normalized.matches("(?s).*\\bday scholars?\\b.*") ? "Day Scholar" : null;
        intent.scholarshipStatus = normalized.matches("(?s).*\\b(scholarship|scholarships|scholarship students)\\b.*")
            ? "Yes" : null;
        intent.gender = normalized.matches("(?s).*\\bfemale\\b.*") ? "Female"
            : normalized.matches("(?s).*\\bmale\\b.*") ? "Male" : null;
        intent.state = stateFromQuery(normalized);
        intent.admissionMode = normalized.contains("srmjee") ? "SRMJEE"
            : normalized.matches("(?s).*(admitted directly|direct admission|directly admitted).*") ? "Direct" : null;

        Matcher attendance = ATTENDANCE_THRESHOLD.matcher(query);
        Matcher marks = MARKS_THRESHOLD.matcher(query);
        if (normalized.contains("certificate") || normalized.contains("certification")) {
            intent.category = "certificate";
            intent.certificateStatus = certificateStatus(normalized);
            if (intent.certificateStatus == null && (normalized.contains("certificate") || normalized.contains("certification"))) {
                intent.certificateStatus = "Any";
            }
            String knownName = knownCertificateIn(normalized);
            Matcher certName = CERTIFICATE_NAME.matcher(query);
            if (knownName != null) {
                intent.certificateName = knownName;
            } else if (certName.find()) {
                intent.certificateName = cleanPhrase(certName.group(1));
            } else {
                intent.certificateName = null;
            }
            intent.interpretedAs = (intent.certificateStatus.equals("Not Completed")
                ? "Not completed" : intent.certificateStatus) + " certificate students"
                + (intent.certificateName == null ? "" : " · " + intent.certificateName)
                + (intent.registerNumber == null ? "" : " · Register Number " + intent.registerNumber);
        } else if (normalized.contains("attendance") || normalized.contains("attendence")) {
            intent.category = "attendance";
            if (attendance.find()) {
                intent.attendanceOperator = operator(attendance.group(1));
                intent.attendanceValue = threshold(attendance.group(2));
            } else if (normalized.contains("low") || normalized.contains("shortage")) {
                intent.attendanceOperator = "LESS_THAN";
                intent.attendanceValue = 75.0;
            } else {
                intent.attendanceOperator = "GREATER_THAN_OR_EQUAL";
                intent.attendanceValue = null;
            }
            intent.interpretedAs = intent.attendanceValue == null ? "Attendance summary"
                : "Attendance " + operatorPhrase(intent.attendanceOperator) + " " + intent.attendanceValue + "%";
        } else if (normalized.matches("(?s).*(\\bmarks?\\b|\\bgrades?\\b|\\bfailed\\b|\\btop students\\b|\\bhighest marks\\b).*")) {
            intent.category = "marks";
            if (marks.find()) {
                intent.marksOperator = operator(marks.group(1));
                intent.marksValue = threshold(marks.group(2));
            } else if (normalized.contains("fail")) {
                intent.marksOperator = "LESS_THAN";
                intent.marksValue = 40.0;
            } else if (normalized.contains("low")) {
                intent.marksOperator = "LESS_THAN";
                intent.marksValue = 50.0;
            } else {
                intent.marksOperator = "GREATER_THAN_OR_EQUAL";
                intent.marksValue = 0.0;
            }
            intent.subjectName = subjectFromQuery(query);
            intent.assessmentName = assessmentFromQuery(query);
            intent.interpretedAs = "Marks " + operatorPhrase(intent.marksOperator) + " " + intent.marksValue + "%"
                + (intent.subjectName == null ? "" : " · Subject contains " + intent.subjectName)
                + (intent.assessmentName == null ? "" : " · Assessment contains " + intent.assessmentName);
        } else {
            intent.careerPlan = careerPlan(normalized);
            intent.companyName = companyFromQuery(query, normalized);
            if (intent.careerPlan != null || intent.companyName != null) {
                intent.category = "career";
                intent.interpretedAs = (intent.careerPlan == null ? "Career interest" : "Career plan contains " + intent.careerPlan)
                    + (intent.companyName == null ? "" : " · Dream company contains " + intent.companyName);
            } else if (intent.registerNumber != null || intent.section != null || intent.residenceType != null || intent.scholarshipStatus != null
                    || intent.gender != null || intent.state != null || intent.admissionMode != null
                    || normalized.contains("student") || normalized.contains("students") || normalized.matches("(?s).*\\b(hostellers?|scholarships?|female|male)\\b.*")) {
                intent.category = "students";
                intent.interpretedAs = describeStudentFilters(intent);
            }
        }
        return intent;
    }

    private QueryResult career(QueryIntent intent) {
        StringBuilder sql = new StringBuilder("SELECT s.first_name, s.last_name, s.register_no, s.section, "
            + "s.official_email, s.student_mobile, s.plan_after_degree, s.dream_company FROM students s WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (intent.companyName != null) {
            List<String> companyTerms = companyTerms(intent.companyName);
            sql.append(" AND (");
            for (int i = 0; i < companyTerms.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(COALESCE(s.dream_company,'')) LIKE ?");
                args.add("%" + companyTerms.get(i).toLowerCase(Locale.ROOT) + "%");
            }
            sql.append(")");
        }
        if (intent.careerPlan != null) {
            sql.append(" AND LOWER(COALESCE(s.plan_after_degree,'')) LIKE ?");
            args.add("%" + intent.careerPlan.toLowerCase(Locale.ROOT) + "%");
        }
        addStudentFilters(sql, args, intent);
        sql.append(" ORDER BY s.register_no");
        List<String> columns = Arrays.asList("Student Name", "Register Number", "Section", "College Email",
            "Student Mobile", "Career Plan", "Dream Company");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("Student Name", fullName(row));
            value.put("Register Number", row.get("register_no"));
            value.put("Section", row.get("section"));
            value.put("College Email", row.get("official_email"));
            value.put("Student Mobile", row.get("student_mobile"));
            value.put("Career Plan", row.get("plan_after_degree"));
            value.put("Dream Company", row.get("dream_company"));
            mapped.add(value);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        if (intent.careerPlan != null) filters.put("Career plan contains", intent.careerPlan);
        if (intent.companyName != null) filters.put("Dream company contains", intent.companyName);
        addDescribedStudentFilters(filters, intent);
        String subject = rows.size() == 1 ? "student" : "students";
        String verb = rows.size() == 1 ? "is" : "are";
        String summary;
        if (intent.companyName != null) {
            summary = rows.size() + " " + subject + " " + verb + " interested in joining " + intent.companyName + ".";
        } else if ("placement".equals(intent.careerPlan)) {
            summary = rows.size() + " " + subject + " " + verb + " interested in placement.";
        } else if ("higher studies".equals(intent.careerPlan)) {
            summary = rows.size() + " " + subject + (rows.size() == 1 ? " plans" : " plan") + " to pursue higher studies.";
        } else if ("entrepreneur".equals(intent.careerPlan)) {
            summary = rows.size() + " " + subject + " " + verb + " interested in entrepreneurship.";
        } else if ("government".equals(intent.careerPlan)) {
            summary = rows.size() + " " + subject + " " + verb + " interested in government exams.";
        } else {
            summary = rows.size() + " " + subject + " match the career filters.";
        }
        return new QueryResult(columns, mapped, filters, summary);
    }

    private QueryResult certificate(QueryIntent intent) {
        StringBuilder sql = new StringBuilder("SELECT s.first_name, s.last_name, s.register_no, s.section, "
            + "c.certificate_name, COALESCE(sc.status, 'Not Started') AS certificate_status, sc.completion_date, "
            + "sc.certificate_number FROM students s CROSS JOIN certificates c "
            + "LEFT JOIN student_certificates sc ON sc.student_id = s.id AND sc.certificate_id = c.id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (intent.certificateName != null) {
            sql.append(" AND LOWER(c.certificate_name) LIKE ?");
            args.add("%" + intent.certificateName.toLowerCase(Locale.ROOT) + "%");
        }
        if ("Not Completed".equals(intent.certificateStatus)) {
            sql.append(" AND (sc.status IS NULL OR LOWER(sc.status) <> 'completed')");
        } else if (!"Any".equals(intent.certificateStatus)) {
            sql.append(" AND LOWER(COALESCE(sc.status, 'not started')) = ?");
            args.add(intent.certificateStatus.toLowerCase(Locale.ROOT));
        }
        addStudentFilters(sql, args, intent);
        sql.append(" ORDER BY c.certificate_name, s.register_no");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<String> columns = Arrays.asList("Student Name", "Register Number", "Section", "Certificate",
            "Certificate Status", "Completion Date", "Certificate Number");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("Student Name", fullName(row));
            value.put("Register Number", row.get("register_no"));
            value.put("Section", row.get("section"));
            value.put("Certificate", row.get("certificate_name"));
            value.put("Certificate Status", row.get("certificate_status"));
            value.put("Completion Date", row.get("completion_date"));
            value.put("Certificate Number", row.get("certificate_number"));
            mapped.add(value);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("Certificate status", intent.certificateStatus);
        if (intent.certificateName != null) filters.put("Certificate contains", intent.certificateName);
        addDescribedStudentFilters(filters, intent);
        String label = intent.certificateName == null ? "certificates" : intent.certificateName;
        String studentWord = rows.size() == 1 ? "student" : "students";
        String have = rows.size() == 1 ? "has" : "have";
        String summary;
        if ("Not Completed".equals(intent.certificateStatus)) {
            summary = rows.size() + " " + studentWord + " " + have + " not completed " + label + ".";
        } else if ("Completed".equals(intent.certificateStatus)) {
            summary = rows.size() + " " + studentWord + " " + have + " completed " + label + ".";
        } else if ("In Progress".equals(intent.certificateStatus)) {
            summary = rows.size() + " " + studentWord + (rows.size() == 1 ? " is" : " are")
                + " pursuing " + label + ".";
        } else if ("Not Started".equals(intent.certificateStatus)) {
            summary = rows.size() + " " + studentWord + " " + have + " not started " + label + ".";
        } else {
            summary = rows.size() + " " + studentWord + " " + have + " a record for " + label + ".";
        }
        return new QueryResult(columns, mapped, filters, summary);
    }

    private QueryResult attendance(QueryIntent intent) {
        StringBuilder sql = new StringBuilder("SELECT s.first_name, s.last_name, s.register_no, s.section, "
            + "COUNT(ar.id) AS total_sessions, "
            + "SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END) AS attended_sessions "
            + "FROM students s LEFT JOIN attendance_records ar ON ar.student_id = s.id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addStudentFilters(sql, args, intent);
        sql.append(" GROUP BY s.id, s.first_name, s.last_name, s.register_no, s.section");
        if (intent.attendanceValue != null) {
            sql.append(" HAVING COUNT(ar.id) > 0 AND (100.0 * SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') "
                + "THEN 1 ELSE 0 END) / COUNT(ar.id)) ").append(sqlOperator(intent.attendanceOperator)).append(" ?");
            args.add(intent.attendanceValue);
        }
        sql.append(" ORDER BY s.register_no");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<String> columns = Arrays.asList("Student Name", "Register Number", "Section", "Attendance %",
            "Attendance Warning", "Present or Attended", "Total Sessions");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int total = ((Number) row.get("total_sessions")).intValue();
            int attended = ((Number) row.get("attended_sessions")).intValue();
            double percentage = total == 0 ? 0 : attended * 100.0 / total;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("Student Name", fullName(row));
            value.put("Register Number", row.get("register_no"));
            value.put("Section", row.get("section"));
            value.put("Attendance %", round(percentage));
            value.put("Attendance Warning", percentage < 75 ? "Critical shortage" : percentage < 85 ? "Needs attention" : "Good attendance");
            value.put("Present or Attended", attended);
            value.put("Total Sessions", total);
            mapped.add(value);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("Attendance", intent.attendanceValue == null ? "All recorded attendance"
            : operatorPhrase(intent.attendanceOperator) + " " + intent.attendanceValue + "%");
        addDescribedStudentFilters(filters, intent);
        return new QueryResult(columns, mapped, filters, rows.size() + (rows.size() == 1 ? " student matches " : " students match ")
            + "the attendance criteria.");
    }

    private QueryResult marks(QueryIntent intent) {
        StringBuilder sql = new StringBuilder("SELECT s.first_name, s.last_name, s.register_no, s.section, "
            + "su.subject_name, a.assessment_name, sm.marks_obtained, a.max_marks, "
            + "(sm.marks_obtained * 100.0 / a.max_marks) AS percentage "
            + "FROM student_marks sm JOIN students s ON s.id = sm.student_id "
            + "JOIN assessments a ON a.id = sm.assessment_id JOIN subjects su ON su.id = a.subject_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (intent.subjectName != null) {
            sql.append(" AND LOWER(su.subject_name) LIKE ?");
            args.add("%" + intent.subjectName.toLowerCase(Locale.ROOT) + "%");
        }
        if (intent.assessmentName != null) {
            sql.append(" AND LOWER(a.assessment_name) LIKE ?");
            args.add("%" + intent.assessmentName.toLowerCase(Locale.ROOT) + "%");
        }
        addStudentFilters(sql, args, intent);
        sql.append(" AND (sm.marks_obtained * 100.0 / a.max_marks) ")
            .append(sqlOperator(intent.marksOperator)).append(" ?");
        args.add(intent.marksValue);
        sql.append(" ORDER BY percentage ").append("GREATER_THAN_OR_EQUAL".equals(intent.marksOperator) ? "DESC" : "ASC")
            .append(", s.register_no");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<String> columns = Arrays.asList("Student Name", "Register Number", "Section", "Subject",
            "Assessment", "Marks Obtained", "Maximum Marks", "Percentage", "Grade");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double percentage = ((Number) row.get("percentage")).doubleValue();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("Student Name", fullName(row));
            value.put("Register Number", row.get("register_no"));
            value.put("Section", row.get("section"));
            value.put("Subject", row.get("subject_name"));
            value.put("Assessment", row.get("assessment_name"));
            value.put("Marks Obtained", row.get("marks_obtained"));
            value.put("Maximum Marks", row.get("max_marks"));
            value.put("Percentage", round(percentage));
            value.put("Grade", grade(percentage));
            mapped.add(value);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("Percentage", operatorPhrase(intent.marksOperator) + " " + intent.marksValue + "%");
        if (intent.subjectName != null) filters.put("Subject contains", intent.subjectName);
        if (intent.assessmentName != null) filters.put("Assessment contains", intent.assessmentName);
        addDescribedStudentFilters(filters, intent);
        return new QueryResult(columns, mapped, filters, rows.size() + (rows.size() == 1 ? " mark record matches " : " mark records match ")
            + "the criteria.");
    }

    private QueryResult students(QueryIntent intent) {
        StringBuilder sql = new StringBuilder("SELECT s.first_name, s.last_name, s.register_no, s.section, "
            + "s.official_email, s.student_mobile, s.gender, s.residency, s.scholarship_status, "
            + "s.tenth_state, s.twelfth_study_state, s.mode_of_admission FROM students s WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addStudentFilters(sql, args, intent);
        sql.append(" ORDER BY s.register_no");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<String> columns = Arrays.asList("Student Name", "Register Number", "Section", "College Email",
            "Student Mobile", "Gender", "Residence", "Scholarship", "Study State", "Mode of Admission");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("Student Name", fullName(row));
            value.put("Register Number", row.get("register_no"));
            value.put("Section", row.get("section"));
            value.put("College Email", row.get("official_email"));
            value.put("Student Mobile", row.get("student_mobile"));
            value.put("Gender", row.get("gender"));
            value.put("Residence", row.get("residency"));
            value.put("Scholarship", row.get("scholarship_status"));
            value.put("Study State", firstValue(row.get("twelfth_study_state"), row.get("tenth_state")));
            value.put("Mode of Admission", row.get("mode_of_admission"));
            mapped.add(value);
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        addDescribedStudentFilters(filters, intent);
        return new QueryResult(columns, mapped, filters, rows.size() + (rows.size() == 1 ? " student matches." : " students match."));
    }

    private static void addStudentFilters(StringBuilder sql, List<Object> args, QueryIntent intent) {
        if (intent.registerNumber != null) {
            sql.append(" AND LOWER(TRIM(COALESCE(s.register_no,''))) = ?");
            args.add(intent.registerNumber.toLowerCase(Locale.ROOT));
        }
        if (intent.section != null) {
            sql.append(" AND LOWER(COALESCE(s.section,'')) = ?");
            args.add(intent.section.toLowerCase(Locale.ROOT));
        }
        if (intent.residenceType != null) {
            if ("Hosteller".equals(intent.residenceType)) {
                sql.append(" AND LOWER(COALESCE(s.residency,'')) LIKE ?");
                args.add("%hostel%");
            } else {
                sql.append(" AND LOWER(COALESCE(s.residency,'')) NOT LIKE ? AND TRIM(COALESCE(s.residency,'')) <> ''");
                args.add("%hostel%");
            }
        }
        if (intent.scholarshipStatus != null) {
            sql.append(" AND LOWER(COALESCE(s.scholarship_status,'')) IN ('yes','y','true')");
        }
        if (intent.gender != null) {
            sql.append(" AND LOWER(COALESCE(s.gender,'')) = ?");
            args.add(intent.gender.toLowerCase(Locale.ROOT));
        }
        if (intent.state != null) {
            sql.append(" AND (LOWER(COALESCE(s.twelfth_study_state,'')) LIKE ? OR LOWER(COALESCE(s.tenth_state,'')) LIKE ?)");
            args.add("%" + intent.state.toLowerCase(Locale.ROOT) + "%");
            args.add("%" + intent.state.toLowerCase(Locale.ROOT) + "%");
        }
        if (intent.admissionMode != null) {
            sql.append(" AND LOWER(COALESCE(s.mode_of_admission,'')) LIKE ?");
            args.add("%" + intent.admissionMode.toLowerCase(Locale.ROOT) + "%");
        }
    }

    private static void addDescribedStudentFilters(Map<String, Object> filters, QueryIntent intent) {
        if (intent.registerNumber != null) filters.put("Register Number", intent.registerNumber);
        if (intent.section != null) filters.put("Section", intent.section);
        if (intent.residenceType != null) filters.put("Residence", intent.residenceType);
        if (intent.scholarshipStatus != null) filters.put("Scholarship", intent.scholarshipStatus);
        if (intent.gender != null) filters.put("Gender", intent.gender);
        if (intent.state != null) filters.put("State contains", intent.state);
        if (intent.admissionMode != null) filters.put("Admission mode contains", intent.admissionMode);
    }

    private String knownCertificateIn(String normalized) {
        for (Map<String, Object> certificate : certificates()) {
            String name = String.valueOf(certificate.get("certificateName"));
            if (normalized.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        return null;
    }

    private static String certificateStatus(String query) {
        if (query.contains("not completed") || query.contains("have not completed") || query.contains("haven't completed")
                || query.contains("not finished") || query.contains("incomplete")) return "Not Completed";
        if (query.contains("in progress") || query.contains("pursuing") || query.contains("currently pursuing")) return "In Progress";
        if (query.contains("not started")) return "Not Started";
        if (query.contains("not applicable")) return "Not Applicable";
        if (query.contains("completed") || query.contains("finish")) return "Completed";
        return "Any";
    }

    private static String careerPlan(String query) {
        if (query.contains("placement") || query.contains("job") || query.contains("joining a company")) return "placement";
        if (query.contains("higher studies") || query.contains("higher study") || query.contains("postgraduate")) return "higher studies";
        if (query.contains("entrepreneur") || query.contains("own business") || query.contains("startup")) return "entrepreneur";
        if (query.contains("government exam") || query.contains("civil service") || query.contains("upsc")) return "government";
        return null;
    }

    private static String companyFromQuery(String query, String normalized) {
        if (careerPlan(normalized) != null && !normalized.matches("(?s).*\\b(dream company|company is|joining [a-z0-9]).*")) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?:dream company(?: is)?|interested in(?: joining)?|joining|join)\\s+(.+)",
            Pattern.CASE_INSENSITIVE).matcher(query);
        if (!matcher.find()) return null;
        String value = matcher.group(1).replaceAll("[?!.].*$", "").trim();
        value = value.replaceFirst("(?i)^(the|a|an)\\s+", "");
        value = value.replaceAll("(?i)\\b(students?|who|want|to|for|placement|job|career|company)\\b", " ").trim();
        value = value.replaceAll("\\s+", " ").replaceAll("^[\\s,.-]+|[\\s,.-]+$", "");
        if (value.length() < 2 || value.matches("(?i)(placement|higher studies|entrepreneurship|government exams?)")) return null;
        return value;
    }

    private static List<String> companyTerms(String company) {
        String value = company.toLowerCase(Locale.ROOT);
        if (value.equals("tcs") || value.contains("tata consultancy services")) {
            return Arrays.asList("tcs", "tata consultancy services");
        }
        if (value.contains("/") || value.contains(" or ")) {
            List<String> terms = new ArrayList<>();
            for (String part : value.split("\\s*/\\s*|\\s+or\\s+")) {
                if (part.trim().length() > 1) terms.add(part.trim());
            }
            return terms.isEmpty() ? Collections.singletonList(value) : terms;
        }
        return Collections.singletonList(value);
    }

    private static String subjectFromQuery(String query) {
        Matcher matcher = Pattern.compile("\\bin\\s+([a-z0-9][a-z0-9 &-]{1,60})", Pattern.CASE_INSENSITIVE).matcher(query);
        if (!matcher.find()) return null;
        String subject = matcher.group(1).replaceAll("(?i)\\b(marks?|grades?|assessment|test|students?|below|above|over|under|less|more|failed|fail)\\b.*$", "").trim();
        return subject.isEmpty() ? null : subject;
    }

    private static String assessmentFromQuery(String query) {
        Matcher matcher = Pattern.compile("\\b(internal\\s+test\\s*\\d+|model\\s+exam|semester\\s+exam|assignment|lab\\s+assessment|project|viva)\\b",
            Pattern.CASE_INSENSITIVE).matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String stateFromQuery(String query) {
        for (String state : Arrays.asList("Tamil Nadu", "Kerala", "Karnataka", "Andhra Pradesh", "Telangana")) {
            if (query.contains(state.toLowerCase(Locale.ROOT))) return state;
        }
        return null;
    }

    private static String describeStudentFilters(QueryIntent intent) {
        List<String> filters = new ArrayList<>();
        if (intent.registerNumber != null) filters.add("Register Number " + intent.registerNumber);
        if (intent.section != null) filters.add("Section " + intent.section);
        if (intent.residenceType != null) filters.add(intent.residenceType);
        if (intent.scholarshipStatus != null) filters.add("Scholarship students");
        if (intent.gender != null) filters.add(intent.gender + " students");
        if (intent.state != null) filters.add("Study state " + intent.state);
        if (intent.admissionMode != null) filters.add("Admission mode " + intent.admissionMode);
        return filters.isEmpty() ? "All students" : String.join(" · ", filters);
    }

    private static String operator(String word) {
        String normalized = word.toLowerCase(Locale.ROOT);
        if (normalized.equals("above") || normalized.equals("over") || normalized.equals("more than")) return "GREATER_THAN";
        if (normalized.equals("at least")) return "GREATER_THAN_OR_EQUAL";
        if (normalized.equals("at most")) return "LESS_THAN_OR_EQUAL";
        return "LESS_THAN";
    }

    private static String sqlOperator(String operator) {
        switch (operator) {
            case "GREATER_THAN": return ">";
            case "GREATER_THAN_OR_EQUAL": return ">=";
            case "LESS_THAN_OR_EQUAL": return "<=";
            case "LESS_THAN":
            default: return "<";
        }
    }

    private static String operatorPhrase(String operator) {
        switch (operator) {
            case "GREATER_THAN": return "above";
            case "GREATER_THAN_OR_EQUAL": return "at least";
            case "LESS_THAN_OR_EQUAL": return "at most";
            case "LESS_THAN":
            default: return "below";
        }
    }

    private static Double threshold(String value) {
        double parsed = Double.parseDouble(value);
        if (parsed < 0 || parsed > 100) throw new IllegalArgumentException("Percentages must be between 0 and 100.");
        return parsed;
    }

    private static String cleanPhrase(String phrase) {
        return phrase.replaceAll("(?i)^(the|a|an)\\s+", "").replaceAll("^[\\s,.-]+|[\\s,.-]+$", "").trim();
    }

    private static String grade(double percentage) {
        if (percentage >= 90) return "O";
        if (percentage >= 80) return "A+";
        if (percentage >= 70) return "A";
        if (percentage >= 60) return "B+";
        if (percentage >= 50) return "B";
        if (percentage >= 40) return "C";
        return "Fail";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String fullName(Map<String, Object> row) {
        return (String.valueOf(row.get("first_name")) + " " + (row.get("last_name") == null ? "" : row.get("last_name")))
            .trim();
    }

    private static Object firstValue(Object first, Object second) {
        return first == null || String.valueOf(first).trim().isEmpty() ? second : first;
    }

    private Map<String, Object> certificateByName(String name) {
        for (Map<String, Object> certificate : certificates()) {
            if (name.equalsIgnoreCase(String.valueOf(certificate.get("certificateName")))) {
                return certificate;
            }
        }
        throw notFound("Certificate not found.");
    }

    private void requireStudent(long studentId) {
        if (!exists("SELECT COUNT(*) FROM students WHERE id = ?", studentId)) throw notFound("Student not found.");
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private static LocalDate optionalDate(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Completion date must use YYYY-MM-DD.");
        }
    }

    private static String requiredText(Object value, String label) {
        String text = optionalText(value);
        if (text.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return text;
    }

    private static String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(NOT_FOUND, message);
    }

    private static final class QueryResult {
        private final List<String> columns;
        private final List<Map<String, Object>> rows;
        private final Map<String, Object> filters;
        private final String summary;

        private QueryResult(List<String> columns, List<Map<String, Object>> rows,
                            Map<String, Object> filters, String summary) {
            this.columns = columns;
            this.rows = rows;
            this.filters = filters;
            this.summary = summary;
        }
    }
}
