package com.srm.studenthub;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AcademicService {
    private static final List<String> ATTENDANCE_STATUSES = Arrays.asList("Present", "Absent", "Late", "On Duty", "Leave");
    private static final List<String> ASSESSMENT_TYPES = Arrays.asList(
        "Internal Test 1", "Internal Test 2", "Model Exam", "Assignment",
        "Lab Assessment", "Semester Exam", "Project", "Viva"
    );
    private final JdbcTemplate jdbc;

    public AcademicService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> attendanceSessions(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder(
            "SELECT se.id, se.attendance_date, se.subject_name, se.hour_or_period, se.faculty_name, se.section, se.created_at, "
                + "COUNT(ar.id) AS record_count, "
                + "SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END) AS attended_count "
                + "FROM attendance_sessions se LEFT JOIN attendance_records ar ON ar.session_id = se.id WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        appendAttendanceFilters(sql, args, filters, "se", "ar");
        sql.append(" GROUP BY se.id, se.attendance_date, se.subject_name, se.hour_or_period, se.faculty_name, se.section, se.created_at")
            .append(" ORDER BY se.attendance_date DESC, se.id DESC");
        List<Map<String, Object>> sessions = lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
        for (Map<String, Object> session : sessions) {
            if (session.get("attendance_date") instanceof Date) {
                session.put("attendance_date", ((Date) session.get("attendance_date")).toLocalDate().toString());
            }
            int total = ((Number) session.get("record_count")).intValue();
            int attended = session.get("attended_count") == null ? 0 : ((Number) session.get("attended_count")).intValue();
            session.put("attendancePercentage", total == 0 ? 0 : round(attended * 100.0 / total));
        }
        return sessions;
    }

    public Map<String, Object> attendanceSession(long id) {
        Map<String, Object> session = one("SELECT id, attendance_date, subject_name, hour_or_period, faculty_name, section, created_at FROM attendance_sessions WHERE id = ?", id);
        if (session == null) {
            throw notFound("Attendance session not found.");
        }
        Date date = (Date) session.get("attendance_date");
        session.put("attendance_date", date.toLocalDate().toString());
        session.put("records", lowerCaseRows(jdbc.queryForList(
            "SELECT s.id AS student_id, s.register_no, s.first_name, s.last_name, ar.status, ar.remarks "
                + "FROM students s LEFT JOIN attendance_records ar ON ar.student_id = s.id AND ar.session_id = ? "
                + "WHERE LOWER(COALESCE(s.section,'')) = LOWER(?) ORDER BY s.register_no",
            id, session.get("section"))));
        return session;
    }

    @Transactional
    public Map<String, Object> saveAttendance(Long id, Map<String, Object> payload) {
        LocalDate date = requiredDate(payload.get("attendanceDate"), "Attendance date");
        String subject = requiredText(payload.get("subjectName"), "Subject");
        String period = requiredText(payload.get("hourOrPeriod"), "Period");
        String section = requiredText(payload.get("section"), "Section");
        String faculty = optionalText(payload.get("facultyName"));
        if (!sectionExists(section)) {
            throw new IllegalArgumentException("The selected section has no students.");
        }
        long sessionId;
        if (id == null) {
            sessionId = insertSession(date, subject, period, faculty, section);
        } else {
            if (jdbc.update("UPDATE attendance_sessions SET attendance_date = ?, subject_name = ?, hour_or_period = ?, faculty_name = ?, section = ? WHERE id = ?",
                    Date.valueOf(date), subject, period, faculty, section, id) == 0) {
                throw notFound("Attendance session not found.");
            }
            sessionId = id;
        }

        Object rawRecords = payload.get("records");
        if (id == null || rawRecords instanceof List) {
            List<Map<String, Object>> records = rawRecords instanceof List
                ? castRecordList(rawRecords) : defaultAttendanceRecords(section);
            saveAttendanceRecords(sessionId, section, records);
        }
        return attendanceSession(sessionId);
    }

    @Transactional
    public void deleteAttendanceSession(long id) {
        if (jdbc.update("DELETE FROM attendance_sessions WHERE id = ?", id) == 0) {
            throw notFound("Attendance session not found.");
        }
    }

    public List<Map<String, Object>> attendanceHistory(long studentId, Map<String, String> filters) {
        requireStudent(studentId);
        StringBuilder sql = new StringBuilder(
            "SELECT se.id AS session_id, se.attendance_date, se.subject_name, se.hour_or_period, se.section, ar.status, ar.remarks "
                + "FROM attendance_records ar JOIN attendance_sessions se ON se.id = ar.session_id WHERE ar.student_id = ?"
        );
        List<Object> args = new ArrayList<>();
        args.add(studentId);
        appendAttendanceFilters(sql, args, filters, "se", "ar");
        sql.append(" ORDER BY se.attendance_date DESC, se.id DESC");
        List<Map<String, Object>> rows = lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
        for (Map<String, Object> row : rows) {
            row.put("attendance_date", ((Date) row.get("attendance_date")).toLocalDate().toString());
        }
        return rows;
    }

    public Map<String, Object> studentAttendanceSummary(long studentId) {
        requireStudent(studentId);
        return attendanceStats(null, null, null).stream()
            .filter(row -> ((Number) row.get("student_id")).longValue() == studentId)
            .findFirst().orElseThrow(() -> new IllegalStateException("Student attendance summary could not be loaded."));
    }

    public List<Map<String, Object>> attendanceShortage(String section) {
        List<Map<String, Object>> students = attendanceStats(section, null, null);
        List<Map<String, Object>> shortage = new ArrayList<>();
        for (Map<String, Object> student : students) {
            Object percentage = student.get("attendancePercentage");
            if (percentage != null && ((Number) percentage).doubleValue() < 75.0) {
                shortage.add(student);
            }
        }
        shortage.sort(Comparator.comparingDouble(student -> ((Number) student.get("attendancePercentage")).doubleValue()));
        return shortage;
    }

    public Map<String, Object> attendanceDashboard(Map<String, String> filters) {
        List<Map<String, Object>> sessions = attendanceSessions(filters);
        List<Map<String, Object>> stats = attendanceStats(filters.get("section"), filters.get("startDate"), filters.get("endDate"));
        double totalPercent = 0;
        int percentCount = 0;
        List<Map<String, Object>> high = new ArrayList<>();
        List<Map<String, Object>> low = new ArrayList<>();
        for (Map<String, Object> stat : stats) {
            if (stat.get("attendancePercentage") != null) {
                totalPercent += ((Number) stat.get("attendancePercentage")).doubleValue();
                percentCount++;
                high.add(stat);
                if (((Number) stat.get("attendancePercentage")).doubleValue() < 75) {
                    low.add(stat);
                }
            }
        }
        high.sort((a, b) -> Double.compare(number(b.get("attendancePercentage")), number(a.get("attendancePercentage"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todaySessions", sessions.stream().filter(s -> LocalDate.now().toString().equals(String.valueOf(s.get("attendance_date")))).count());
        result.put("averageAttendance", percentCount == 0 ? 0 : round(totalPercent / percentCount));
        result.put("shortageCount", low.size());
        result.put("highestAttendance", high.subList(0, Math.min(5, high.size())));
        result.put("shortageStudents", low);
        result.put("recentSessions", sessions.subList(0, Math.min(8, sessions.size())));
        return result;
    }

    public List<Map<String, Object>> attendanceStats(String section, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id AS student_id, s.register_no, s.first_name, s.last_name, s.section, "
                + "COUNT(se.id) AS total_sessions, "
                + "SUM(CASE WHEN ar.status = 'Present' THEN 1 ELSE 0 END) AS present_count, "
                + "SUM(CASE WHEN ar.status = 'Absent' THEN 1 ELSE 0 END) AS absent_count, "
                + "SUM(CASE WHEN ar.status = 'Late' THEN 1 ELSE 0 END) AS late_count, "
                + "SUM(CASE WHEN ar.status = 'On Duty' THEN 1 ELSE 0 END) AS on_duty_count, "
                + "SUM(CASE WHEN ar.status = 'Leave' THEN 1 ELSE 0 END) AS leave_count, "
                + "SUM(CASE WHEN ar.status IN ('Present','Late','On Duty') THEN 1 ELSE 0 END) AS attended_count "
                + "FROM students s LEFT JOIN attendance_records ar ON ar.student_id = s.id "
                + "LEFT JOIN attendance_sessions se ON se.id = ar.session_id"
        );
        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (hasText(section)) {
            where.add("LOWER(COALESCE(s.section,'')) = LOWER(?)");
            args.add(section.trim());
        }
        if (hasText(startDate)) {
            where.add("(se.attendance_date >= ? OR se.id IS NULL)");
            args.add(Date.valueOf(parseDate(startDate, "Start date")));
        }
        if (hasText(endDate)) {
            where.add("(se.attendance_date <= ? OR se.id IS NULL)");
            args.add(Date.valueOf(parseDate(endDate, "End date")));
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" GROUP BY s.id, s.register_no, s.first_name, s.last_name, s.section ORDER BY s.register_no");
        List<Map<String, Object>> results = lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
        for (Map<String, Object> student : results) {
            int total = ((Number) student.get("total_sessions")).intValue();
            int attended = ((Number) student.get("attended_count")).intValue();
            student.put("attendancePercentage", total == 0 ? null : round(100.0 * attended / total));
            student.put("attendanceIndicator", total == 0 ? "No records"
                : ((Number) student.get("attendancePercentage")).doubleValue() < 75 ? "Critical shortage"
                : ((Number) student.get("attendancePercentage")).doubleValue() < 85 ? "Needs attention" : "Good attendance");
        }
        return results;
    }

    public List<Map<String, Object>> subjects(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("SELECT * FROM subjects WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendEquals(sql, args, filters.get("section"), "section");
        appendEquals(sql, args, filters.get("semester"), "semester");
        appendEquals(sql, args, filters.get("academicYear"), "academic_year");
        if (hasText(filters.get("query"))) {
            sql.append(" AND (LOWER(subject_name) LIKE ? OR LOWER(subject_code) LIKE ?)");
            String pattern = "%" + filters.get("query").trim().toLowerCase(Locale.ROOT) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY semester, subject_name");
        return lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @Transactional
    public Map<String, Object> saveSubject(Long id, Map<String, Object> payload) {
        String code = requiredText(payload.get("subjectCode"), "Subject code");
        String name = requiredText(payload.get("subjectName"), "Subject name");
        String semester = requiredText(payload.get("semester"), "Semester");
        String year = requiredText(payload.get("academicYear"), "Academic year");
        String section = requiredText(payload.get("section"), "Section");
        BigDecimal internal = decimal(payload.get("maxInternalMarks"), "Maximum internal marks", true);
        BigDecimal external = decimal(payload.get("maxExternalMarks"), "Maximum external marks", true);
        BigDecimal total = decimal(payload.get("maxTotalMarks"), "Maximum total marks", false);
        if (internal.add(external).compareTo(total) > 0) {
            throw new IllegalArgumentException("Maximum total marks cannot be less than the internal plus external maximum.");
        }
        if (!sectionExists(section)) {
            throw new IllegalArgumentException("The selected section has no students.");
        }
        if (id == null) {
            long createdId = insertAndGetId(
                "INSERT INTO subjects (subject_code, subject_name, semester, academic_year, section, max_internal_marks, max_external_marks, max_total_marks) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                code, name, semester, year, section, internal, external, total);
            return subject(createdId);
        }
        if (jdbc.update("UPDATE subjects SET subject_code = ?, subject_name = ?, semester = ?, academic_year = ?, section = ?, max_internal_marks = ?, max_external_marks = ?, max_total_marks = ? WHERE id = ?",
                code, name, semester, year, section, internal, external, total, id) == 0) {
            throw notFound("Subject not found.");
        }
        return subject(id);
    }

    @Transactional
    public void deleteSubject(long id) {
        if (jdbc.update("DELETE FROM subjects WHERE id = ?", id) == 0) {
            throw notFound("Subject not found.");
        }
    }

    public List<Map<String, Object>> assessments(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder(
            "SELECT a.*, s.subject_code, s.subject_name, s.semester, s.academic_year, s.section "
                + "FROM assessments a JOIN subjects s ON s.id = a.subject_id WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        appendEquals(sql, args, filters.get("subjectId"), "s.id");
        appendEquals(sql, args, filters.get("section"), "s.section");
        appendEquals(sql, args, filters.get("semester"), "s.semester");
        appendEquals(sql, args, filters.get("academicYear"), "s.academic_year");
        appendEquals(sql, args, filters.get("assessmentId"), "a.id");
        sql.append(" ORDER BY a.assessment_date DESC, a.id DESC");
        List<Map<String, Object>> result = lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
        for (Map<String, Object> assessment : result) {
            if (assessment.get("assessment_date") instanceof Date) {
                assessment.put("assessment_date", ((Date) assessment.get("assessment_date")).toLocalDate().toString());
            }
        }
        return result;
    }

    @Transactional
    public Map<String, Object> saveAssessment(Long id, Map<String, Object> payload) {
        long subjectId = requiredLong(payload.get("subjectId"), "Subject");
        if (subject(subjectId) == null) {
            throw notFound("Subject not found.");
        }
        String name = requiredText(payload.get("assessmentName"), "Assessment name");
        String type = requiredText(payload.get("assessmentType"), "Assessment type");
        if (!ASSESSMENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("Choose a supported assessment type.");
        }
        BigDecimal maxMarks = decimal(payload.get("maxMarks"), "Maximum marks", false);
        BigDecimal weightage = payload.get("weightage") == null ? BigDecimal.valueOf(100)
            : decimal(payload.get("weightage"), "Weightage", true);
        Date date = payload.get("assessmentDate") == null || optionalText(payload.get("assessmentDate")).isEmpty()
            ? null : Date.valueOf(parseDate(payload.get("assessmentDate"), "Assessment date"));
        if (id == null) {
            long createdId = insertAndGetId(
                "INSERT INTO assessments (subject_id, assessment_name, assessment_type, assessment_date, max_marks, weightage) VALUES (?, ?, ?, ?, ?, ?)",
                subjectId, name, type, date, maxMarks, weightage);
            return assessments(Collections.singletonMap("assessmentId", String.valueOf(createdId))).get(0);
        }
        if (subject(subjectId) == null) {
            throw notFound("Subject not found.");
        }
        Integer oversizedMarks = jdbc.queryForObject("SELECT COUNT(*) FROM student_marks WHERE assessment_id = ? AND marks_obtained > ?",
            Integer.class, id, maxMarks);
        if (oversizedMarks != null && oversizedMarks > 0) {
            throw new IllegalArgumentException("The new maximum is below marks already recorded for this assessment.");
        }
        if (jdbc.update("UPDATE assessments SET subject_id = ?, assessment_name = ?, assessment_type = ?, assessment_date = ?, max_marks = ?, weightage = ? WHERE id = ?",
                subjectId, name, type, date, maxMarks, weightage, id) == 0) {
            throw notFound("Assessment not found.");
        }
        return assessments(Collections.singletonMap("assessmentId", String.valueOf(id))).get(0);
    }

    @Transactional
    public void deleteAssessment(long id) {
        if (jdbc.update("DELETE FROM assessments WHERE id = ?", id) == 0) {
            throw notFound("Assessment not found.");
        }
    }

    public List<Map<String, Object>> marks(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id AS student_id, s.register_no, s.first_name, s.last_name, s.section, s.academic_year, "
                + "a.id AS assessment_id, a.assessment_name, a.assessment_type, a.assessment_date, a.max_marks, a.weightage, "
                + "su.id AS subject_id, su.subject_code, su.subject_name, su.semester, su.max_total_marks, sm.id AS mark_id, sm.marks_obtained, sm.remarks "
                + "FROM assessments a JOIN subjects su ON su.id = a.subject_id "
                + "JOIN students s ON LOWER(COALESCE(s.section,'')) = LOWER(su.section) "
                + "LEFT JOIN student_marks sm ON sm.assessment_id = a.id AND sm.student_id = s.id WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        appendEquals(sql, args, filters.get("assessmentId"), "a.id");
        appendEquals(sql, args, filters.get("subjectId"), "su.id");
        appendEquals(sql, args, filters.get("section"), "su.section");
        appendEquals(sql, args, filters.get("semester"), "su.semester");
        appendEquals(sql, args, filters.get("academicYear"), "su.academic_year");
        appendEquals(sql, args, filters.get("studentId"), "s.id");
        appendEquals(sql, args, filters.get("assessmentType"), "a.assessment_type");
        if (hasText(filters.get("query"))) {
            String pattern = "%" + filters.get("query").trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" AND (LOWER(s.register_no) LIKE ? OR LOWER(s.first_name) LIKE ? OR LOWER(s.last_name) LIKE ?)");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if ("true".equalsIgnoreCase(filters.get("missingOnly"))) {
            sql.append(" AND sm.id IS NULL");
        }
        sql.append(" ORDER BY su.subject_name, a.assessment_date, a.id, s.register_no");
        List<Map<String, Object>> result = lowerCaseRows(jdbc.queryForList(sql.toString(), args.toArray()));
        for (Map<String, Object> row : result) {
            if (row.get("assessment_date") instanceof Date) {
                row.put("assessment_date", ((Date) row.get("assessment_date")).toLocalDate().toString());
            }
            Object marksValue = row.get("marks_obtained");
            Object maxValue = row.get("max_marks");
            row.put("missing", row.get("mark_id") == null);
            row.put("percentage", marksValue == null ? null : round(number(marksValue) * 100.0 / number(maxValue)));
            row.put("grade", marksValue == null ? null : grade(number(marksValue) * 100.0 / number(maxValue)));
            row.put("pass", marksValue != null && number(marksValue) * 100.0 / number(maxValue) >= 40.0);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> saveBulkMarks(Map<String, Object> payload) {
        long assessmentId = requiredLong(payload.get("assessmentId"), "Assessment");
        Map<String, Object> assessment = one("SELECT id, subject_id, max_marks FROM assessments WHERE id = ?", assessmentId);
        if (assessment == null) {
            throw notFound("Assessment not found.");
        }
        BigDecimal maxMarks = (BigDecimal) assessment.get("max_marks");
        Object raw = payload.get("records");
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("Marks records must be a list.");
        }
        long subjectId = ((Number) assessment.get("subject_id")).longValue();
        Map<String, Object> subject = subject(subjectId);
        Set<Long> studentIds = new HashSet<>();
        int saved = 0;
        for (Map<String, Object> record : castRecordList(raw)) {
            long studentId = requiredLong(record.get("studentId"), "Student");
            if (!studentIds.add(studentId)) {
                throw new IllegalArgumentException("A student can only be included once per assessment.");
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE id = ? AND LOWER(COALESCE(section,'')) = LOWER(?)",
                    Integer.class, studentId, subject.get("section")) == 0) {
                throw new IllegalArgumentException("Every student must belong to the assessment section.");
            }
            Object rawMarks = record.get("marksObtained");
            if (rawMarks == null || optionalText(rawMarks).isEmpty()) {
                jdbc.update("DELETE FROM student_marks WHERE student_id = ? AND assessment_id = ?", studentId, assessmentId);
                continue;
            }
            BigDecimal marksObtained = decimal(rawMarks, "Marks obtained", true);
            if (marksObtained.compareTo(maxMarks) > 0) {
                throw new IllegalArgumentException("Marks cannot exceed the assessment maximum of " + maxMarks + ".");
            }
            String remarks = optionalText(record.get("remarks"));
            int updated = jdbc.update("UPDATE student_marks SET marks_obtained = ?, remarks = ?, updated_at = CURRENT_TIMESTAMP WHERE student_id = ? AND assessment_id = ?",
                marksObtained, remarks, studentId, assessmentId);
            if (updated == 0) {
                jdbc.update("INSERT INTO student_marks (student_id, assessment_id, marks_obtained, remarks) VALUES (?, ?, ?, ?)",
                    studentId, assessmentId, marksObtained, remarks);
            }
            saved++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        result.put("message", saved + " marks records saved.");
        return result;
    }

    @Transactional
    public Map<String, Object> updateMark(long id, Map<String, Object> payload) {
        Map<String, Object> mark = one("SELECT sm.id, sm.assessment_id, a.max_marks FROM student_marks sm JOIN assessments a ON a.id = sm.assessment_id WHERE sm.id = ?", id);
        if (mark == null) {
            throw notFound("Mark record not found.");
        }
        BigDecimal marksObtained = decimal(payload.get("marksObtained"), "Marks obtained", true);
        BigDecimal maximum = (BigDecimal) mark.get("max_marks");
        if (marksObtained.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Marks cannot exceed the assessment maximum of " + maximum + ".");
        }
        jdbc.update("UPDATE student_marks SET marks_obtained = ?, remarks = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            marksObtained, optionalText(payload.get("remarks")), id);
        return marks(Collections.singletonMap("assessmentId", String.valueOf(mark.get("assessment_id"))))
            .stream().filter(row -> ((Number) row.get("mark_id")).longValue() == id).findFirst().orElseThrow(
                () -> new IllegalStateException("Updated mark could not be loaded."));
    }

    @Transactional
    public void deleteMark(long id) {
        if (jdbc.update("DELETE FROM student_marks WHERE id = ?", id) == 0) {
            throw notFound("Mark record not found.");
        }
    }

    public List<Map<String, Object>> studentMarks(long studentId) {
        requireStudent(studentId);
        return marks(Collections.singletonMap("studentId", String.valueOf(studentId)));
    }

    public Map<String, Object> marksPerformance(Map<String, String> filters) {
        List<Map<String, Object>> rows = marks(filters);
        Map<Long, Map<String, Object>> students = new LinkedHashMap<>();
        Map<String, Map<String, Object>> subjects = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long studentId = ((Number) row.get("student_id")).longValue();
            Map<String, Object> student = students.computeIfAbsent(studentId, key -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("studentId", key);
                value.put("registerNo", row.get("register_no"));
                value.put("firstName", row.get("first_name"));
                value.put("lastName", row.get("last_name"));
                value.put("section", row.get("section"));
                value.put("earned", 0.0);
                value.put("possible", 0.0);
                value.put("weightedEarned", 0.0);
                value.put("weightTotal", 0.0);
                value.put("internalEarned", 0.0);
                value.put("internalPossible", 0.0);
                value.put("missingAssessments", 0);
                value.put("subjects", new LinkedHashMap<String, Map<String, Object>>());
                return value;
            });
            Map<String, Map<String, Object>> studentSubjects = castMap(student.get("subjects"));
            String subjectKey = String.valueOf(row.get("subject_id"));
            Map<String, Object> subjectSummary = subjects.computeIfAbsent(subjectKey, key -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("subjectId", row.get("subject_id"));
                value.put("subjectName", row.get("subject_name"));
                value.put("subjectCode", row.get("subject_code"));
                value.put("earned", 0.0);
                value.put("possible", 0.0);
                return value;
            });
            Map<String, Object> subject = studentSubjects.computeIfAbsent(subjectKey, key -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("subjectId", row.get("subject_id"));
                value.put("subjectName", row.get("subject_name"));
                value.put("subjectCode", row.get("subject_code"));
                value.put("semester", row.get("semester"));
                value.put("earned", 0.0);
                value.put("possible", 0.0);
                value.put("weightedEarned", 0.0);
                value.put("weightTotal", 0.0);
                value.put("maxTotalMarks", row.get("max_total_marks"));
                value.put("assessments", new ArrayList<Map<String, Object>>());
                return value;
            });
            ((List<Map<String, Object>>) subject.get("assessments")).add(row);
            if (Boolean.TRUE.equals(row.get("missing"))) {
                student.put("missingAssessments", ((Number) student.get("missingAssessments")).intValue() + 1);
                continue;
            }
            double earned = number(row.get("marks_obtained"));
            double possible = number(row.get("max_marks"));
            double weightage = number(row.get("weightage"));
            double weightedEarned = possible == 0 ? 0 : earned * 100.0 / possible * weightage;
            student.put("earned", number(student.get("earned")) + earned);
            student.put("possible", number(student.get("possible")) + possible);
            student.put("weightedEarned", number(student.get("weightedEarned")) + weightedEarned);
            student.put("weightTotal", number(student.get("weightTotal")) + weightage);
            if (!"Semester Exam".equals(row.get("assessment_type"))) {
                student.put("internalEarned", number(student.get("internalEarned")) + earned);
                student.put("internalPossible", number(student.get("internalPossible")) + possible);
            }
            subject.put("earned", number(subject.get("earned")) + earned);
            subject.put("possible", number(subject.get("possible")) + possible);
            subject.put("weightedEarned", number(subject.get("weightedEarned")) + weightedEarned);
            subject.put("weightTotal", number(subject.get("weightTotal")) + weightage);
            subjectSummary.put("weightedEarned", number(subjectSummary.get("weightedEarned")) + weightedEarned);
            subjectSummary.put("weightTotal", number(subjectSummary.get("weightTotal")) + weightage);
        }
        List<Map<String, Object>> rankings = new ArrayList<>();
        for (Map<String, Object> student : students.values()) {
            double rawPossible = number(student.get("possible"));
            double weightTotal = number(student.get("weightTotal"));
            double pct = rawPossible == 0 ? 0 : weightTotal == 0
                ? number(student.get("earned")) * 100 / rawPossible
                : number(student.get("weightedEarned")) / weightTotal;
            boolean hasMarks = rawPossible > 0;
            student.put("percentage", hasMarks ? round(pct) : null);
            double internalPossible = number(student.get("internalPossible"));
            student.put("internalAverage", internalPossible == 0 ? null
                : round(number(student.get("internalEarned")) * 100.0 / internalPossible));
            student.put("grade", hasMarks ? grade(pct) : null);
            student.put("pass", hasMarks && pct >= 40);
            student.put("performanceIndicator", !hasMarks ? "No marks"
                : pct >= 75 ? "Good" : pct >= 50 ? "Needs attention" : "Needs support");
            for (Map<String, Object> subject : castMap(student.get("subjects")).values()) {
                double subjectPossible = number(subject.get("possible"));
                double subjectWeight = number(subject.get("weightTotal"));
                double subjectPct = subjectPossible == 0 ? 0 : subjectWeight == 0
                    ? number(subject.get("earned")) * 100 / subjectPossible
                    : number(subject.get("weightedEarned")) / subjectWeight;
                boolean subjectHasMarks = subjectPossible > 0;
                subject.put("percentage", subjectHasMarks ? round(subjectPct) : null);
                subject.put("grade", subjectHasMarks ? grade(subjectPct) : null);
                subject.put("totalMarks", subjectHasMarks
                    ? round(subjectPct * number(subject.get("maxTotalMarks")) / 100.0) : null);
                subject.put("maxMarks", round(number(subject.get("maxTotalMarks"))));
            }
            rankings.add(student);
        }
        rankings.sort((left, right) -> {
            int percent = Double.compare(number(right.get("percentage")), number(left.get("percentage")));
            return percent != 0 ? percent : String.valueOf(left.get("registerNo")).compareTo(String.valueOf(right.get("registerNo")));
        });
        Map<String, Integer> sectionRanks = new HashMap<>();
        for (Map<String, Object> student : rankings) {
            String section = String.valueOf(student.get("section"));
            if (number(student.get("possible")) > 0) {
                int rank = sectionRanks.getOrDefault(section, 0) + 1;
                sectionRanks.put(section, rank);
                student.put("rank", rank);
            } else {
                student.put("rank", null);
            }
        }
        double total = 0;
        double highest = 0;
        double lowest = 100;
        int count = 0;
        int support = 0;
        for (Map<String, Object> student : rankings) {
            if (number(student.get("possible")) == 0) {
                continue;
            }
            double percentage = number(student.get("percentage"));
            total += percentage;
            highest = Math.max(highest, percentage);
            lowest = Math.min(lowest, percentage);
            count++;
            if (percentage < 50) {
                support++;
            }
        }
        List<Map<String, Object>> subjectSummaries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : subjects.entrySet()) {
            Map<String, Object> subject = entry.getValue();
            double weightTotal = number(subject.get("weightTotal"));
            double subjectPct = weightTotal == 0 ? 0 : number(subject.get("weightedEarned")) / weightTotal;
            boolean hasMarks = weightTotal > 0;
            subject.put("percentage", hasMarks ? round(subjectPct) : null);
            subject.put("grade", hasMarks ? grade(subjectPct) : null);
            subjectSummaries.add(subject);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("students", rankings);
        result.put("classAverage", count == 0 ? 0 : round(total / count));
        result.put("highestMark", count == 0 ? 0 : round(highest));
        result.put("lowestMark", count == 0 ? 0 : round(lowest));
        result.put("studentsNeedingSupport", support);
        result.put("subjectPerformance", subjectSummaries);
        result.put("recentAssessments", assessments(filters).stream().limit(8).collect(java.util.stream.Collectors.toList()));
        return result;
    }

    public List<String> assessmentTypes() {
        return ASSESSMENT_TYPES;
    }

    private List<Map<String, Object>> defaultAttendanceRecords(String section) {
        return lowerCaseRows(jdbc.queryForList("SELECT id AS student_id, 'Present' AS status, '' AS remarks FROM students WHERE LOWER(COALESCE(section,'')) = LOWER(?) ORDER BY register_no",
            section));
    }

    private void saveAttendanceRecords(long sessionId, String section, List<Map<String, Object>> records) {
        Map<Long, Map<String, Object>> byStudent = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            long studentId = requiredLong(record.get("studentId") == null ? record.get("student_id") : record.get("studentId"), "Student");
            if (byStudent.put(studentId, record) != null) {
                throw new IllegalArgumentException("A student can only be marked once per session.");
            }
            String status = requiredText(record.get("status"), "Attendance status");
            if (!ATTENDANCE_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Choose a valid attendance status.");
            }
            record.put("status", status);
        }
        List<Map<String, Object>> sectionStudents = jdbc.queryForList(
            "SELECT id FROM students WHERE LOWER(COALESCE(section,'')) = LOWER(?)", section);
        if (byStudent.size() != sectionStudents.size()) {
            throw new IllegalArgumentException("Attendance must include every student in the selected section.");
        }
        for (Map<String, Object> student : sectionStudents) {
            long studentId = ((Number) student.get("id")).longValue();
            Map<String, Object> record = byStudent.get(studentId);
            if (record == null) {
                throw new IllegalArgumentException("Attendance must include every student in the selected section.");
            }
        }
        jdbc.update("DELETE FROM attendance_records WHERE session_id = ?", sessionId);
        for (Map.Entry<Long, Map<String, Object>> entry : byStudent.entrySet()) {
            Map<String, Object> record = entry.getValue();
            jdbc.update("INSERT INTO attendance_records (session_id, student_id, status, remarks) VALUES (?, ?, ?, ?)",
                sessionId, entry.getKey(), record.get("status"), optionalText(record.get("remarks")));
        }
    }

    private long insertSession(LocalDate date, String subject, String period, String faculty, String section) {
        org.springframework.jdbc.support.GeneratedKeyHolder holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO attendance_sessions (attendance_date, subject_name, hour_or_period, faculty_name, section) VALUES (?, ?, ?, ?, ?)",
                new String[]{"ID"});
            statement.setDate(1, Date.valueOf(date));
            statement.setString(2, subject);
            statement.setString(3, period);
            statement.setString(4, faculty);
            statement.setString(5, section);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("The database did not return the attendance session ID.");
        }
        return key.longValue();
    }

    private Map<String, Object> subject(long id) {
        return one("SELECT * FROM subjects WHERE id = ?", id);
    }

    private boolean sectionExists(String section) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE LOWER(COALESCE(section,'')) = LOWER(?)",
            Integer.class, section);
        return count != null && count > 0;
    }

    private void requireStudent(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw notFound("Student not found.");
        }
    }

    private static void appendAttendanceFilters(StringBuilder sql, List<Object> args, Map<String, String> filters,
                                                String sessionAlias, String recordAlias) {
        if (hasText(filters.get("startDate"))) {
            sql.append(" AND ").append(sessionAlias).append(".attendance_date >= ?");
            args.add(Date.valueOf(parseDate(filters.get("startDate"), "Start date")));
        }
        if (hasText(filters.get("endDate"))) {
            sql.append(" AND ").append(sessionAlias).append(".attendance_date <= ?");
            args.add(Date.valueOf(parseDate(filters.get("endDate"), "End date")));
        }
        if (hasText(filters.get("date"))) {
            sql.append(" AND ").append(sessionAlias).append(".attendance_date = ?");
            args.add(Date.valueOf(parseDate(filters.get("date"), "Date")));
        }
        appendEquals(sql, args, filters.get("section"), sessionAlias + ".section");
        if (hasText(filters.get("subject"))) {
            sql.append(" AND LOWER(").append(sessionAlias).append(".subject_name) LIKE ?");
            args.add("%" + filters.get("subject").trim().toLowerCase(Locale.ROOT) + "%");
        }
        appendEquals(sql, args, filters.get("studentId"), recordAlias + ".student_id");
        appendEquals(sql, args, filters.get("status"), recordAlias + ".status");
    }

    private static void appendEquals(StringBuilder sql, List<Object> args, String value, String column) {
        if (hasText(value)) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> result = jdbc.queryForList(sql, args);
        return result.isEmpty() ? null : lowerCaseKeys(result.get(0));
    }

    private long insertAndGetId(String sql, Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(sql, new String[]{"ID"});
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("The database did not return the new record ID.");
        }
        return key.longValue();
    }

    private static List<Map<String, Object>> lowerCaseRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(lowerCaseKeys(row));
        return result;
    }

    private static Map<String, Object> lowerCaseKeys(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> castRecordList(Object raw) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("Each record must be an object.");
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                record.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            records.add(record);
        }
        return records;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castMap(Object value) {
        return (Map<String, Map<String, Object>>) value;
    }

    private static LocalDate requiredDate(Object raw, String label) {
        if (!hasText(optionalText(raw))) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return parseDate(raw, label);
    }

    private static LocalDate parseDate(Object raw, String label) {
        try {
            return LocalDate.parse(optionalText(raw));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(label + " must be a valid date in YYYY-MM-DD format.");
        }
    }

    private static String requiredText(Object raw, String label) {
        String value = optionalText(raw).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value;
    }

    private static long requiredLong(Object raw, String label) {
        try {
            long value = Long.parseLong(optionalText(raw));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be selected.");
        }
    }

    private static BigDecimal decimal(Object raw, String label, boolean allowZero) {
        try {
            BigDecimal value = new BigDecimal(optionalText(raw));
            if (value.scale() > 2 || value.signum() < 0 || (!allowZero && value.signum() == 0)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + (allowZero ? " must be a non-negative number." : " must be greater than zero."));
        }
    }

    private static String optionalText(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static String grade(double percentage) {
        if (percentage >= 90) return "O";
        if (percentage >= 80) return "A+";
        if (percentage >= 70) return "A";
        if (percentage >= 60) return "B+";
        if (percentage >= 50) return "B";
        if (percentage >= 40) return "C";
        return "Fail";
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
