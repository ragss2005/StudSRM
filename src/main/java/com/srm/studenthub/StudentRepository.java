package com.srm.studenthub;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class StudentRepository {
    private static final String COLUMNS = String.join(", ", StudentFieldCatalog.keys());
    private final JdbcTemplate jdbc;

    public StudentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String query, String section, String residency, String scholarship) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, first_name, last_name, official_email, register_no, academic_year, section, residency, scholarship_status FROM students WHERE 1=1"
        );
        List<Object> args = new ArrayList<>();
        if (hasText(query)) {
            String pattern = "%" + query.trim().toLowerCase() + "%";
            sql.append(" AND (LOWER(COALESCE(first_name, '')) LIKE ? OR LOWER(COALESCE(last_name, '')) LIKE ?")
                .append(" OR LOWER(CONCAT(CONCAT(COALESCE(first_name, ''), ' '), COALESCE(last_name, ''))) LIKE ?")
                .append(" OR LOWER(COALESCE(register_no, '')) LIKE ? OR LOWER(COALESCE(admission_no, '')) LIKE ?")
                .append(" OR LOWER(COALESCE(official_email, '')) LIKE ?)");
            for (int i = 0; i < 6; i++) {
                args.add(pattern);
            }
        }
        if (hasText(section)) {
            sql.append(" AND LOWER(COALESCE(section, '')) = ?");
            args.add(section.trim().toLowerCase());
        }
        if ("hosteller".equalsIgnoreCase(residency)) {
            sql.append(" AND LOWER(COALESCE(residency, '')) LIKE '%hostel%'");
        } else if ("dayscholar".equalsIgnoreCase(residency)) {
            sql.append(" AND LOWER(COALESCE(residency, '')) NOT LIKE '%hostel%'")
                .append(" AND TRIM(COALESCE(residency, '')) <> ''");
        }
        if ("yes".equalsIgnoreCase(scholarship)) {
            sql.append(" AND LOWER(COALESCE(scholarship_status, '')) IN ('yes', 'y', 'true')");
        } else if ("no".equalsIgnoreCase(scholarship)) {
            sql.append(" AND LOWER(COALESCE(scholarship_status, '')) NOT IN ('yes', 'y', 'true')");
        }
        sql.append(" ORDER BY register_no");
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("id", rs.getLong("id"));
            student.put("first_name", rs.getString("first_name"));
            student.put("last_name", rs.getString("last_name"));
            student.put("official_email", rs.getString("official_email"));
            student.put("register_no", rs.getString("register_no"));
            student.put("academic_year", rs.getString("academic_year"));
            student.put("section", rs.getString("section"));
            student.put("residency", rs.getString("residency"));
            student.put("scholarship_status", rs.getString("scholarship_status"));
            return student;
        }, args.toArray());
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM students WHERE id = ?", (rs, rowNum) -> {
                Map<String, Object> student = new LinkedHashMap<>();
                student.put("id", rs.getLong("id"));
                for (String key : StudentFieldCatalog.keys()) {
                    student.put(key, rs.getString(key));
                }
                student.put("created_at", rs.getTimestamp("created_at"));
                student.put("updated_at", rs.getTimestamp("updated_at"));
                return student;
            }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findByRegisterNo(String registerNo) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM students WHERE register_no = ?", (rs, rowNum) -> {
                Map<String, Object> student = new LinkedHashMap<>();
                student.put("id", rs.getLong("id"));
                for (String key : StudentFieldCatalog.keys()) {
                    student.put(key, rs.getString(key));
                }
                return student;
            }, registerNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(Map<String, String> student) {
        String sql = "INSERT INTO students (" + COLUMNS + ") VALUES (" + placeholders(StudentFieldCatalog.keys().size()) + ")";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"ID"});
            bindFields(statement, student, 1);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("The database did not return the new student ID.");
        }
        return key.longValue();
    }

    public void update(long id, Map<String, String> student) {
        StringBuilder sql = new StringBuilder("UPDATE students SET ");
        for (int i = 0; i < StudentFieldCatalog.keys().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(StudentFieldCatalog.keys().get(i)).append(" = ?");
        }
        sql.append(", updated_at = CURRENT_TIMESTAMP WHERE id = ?");
        jdbc.update(sql.toString(), statement -> {
            bindFields(statement, student, 1);
            statement.setLong(StudentFieldCatalog.keys().size() + 1, id);
        });
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class);
        return count == null ? 0 : count;
    }

    public List<String> sections() {
        return jdbc.query("SELECT DISTINCT section FROM students WHERE section IS NOT NULL AND TRIM(section) <> '' ORDER BY section",
            (rs, rowNum) -> rs.getString(1));
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM students WHERE id = ?", id);
    }

    public Map<String, String> customValues(long studentId) {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("SELECT field_key, field_value FROM student_custom_values WHERE student_id = ?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                values.put(rs.getString("field_key"), rs.getString("field_value")), studentId);
        return values;
    }

    public List<Map<String, Object>> customFields() {
        return jdbc.query("SELECT field_key, label, field_type, created_at FROM custom_fields ORDER BY label",
            (rs, rowNum) -> {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("key", rs.getString("field_key"));
                field.put("label", rs.getString("label"));
                field.put("type", rs.getString("field_type"));
                field.put("createdAt", rs.getTimestamp("created_at"));
                return field;
            });
    }

    public void insertCustomField(String key, String label, String type) {
        jdbc.update("INSERT INTO custom_fields (field_key, label, field_type) VALUES (?, ?, ?)", key, label, type);
    }

    public void setCustomValue(long studentId, String key, String value) {
        int updated = jdbc.update("UPDATE student_custom_values SET field_value = ? WHERE student_id = ? AND field_key = ?",
            value, studentId, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO student_custom_values (student_id, field_key, field_value) VALUES (?, ?, ?)",
                studentId, key, value);
        }
    }

    public void deleteCustomField(String key) {
        jdbc.update("DELETE FROM student_custom_values WHERE field_key = ?", key);
        jdbc.update("DELETE FROM custom_fields WHERE field_key = ?", key);
    }

    private static void bindFields(PreparedStatement statement, Map<String, String> student, int startIndex)
        throws java.sql.SQLException {
        int index = startIndex;
        for (String key : StudentFieldCatalog.keys()) {
            statement.setString(index++, student.get(key));
        }
    }

    private static String placeholders(int count) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        return placeholders.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
