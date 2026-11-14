package com.srm.studenthub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StudentFieldCatalog {
    public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
        "register_no", "admission_no", "first_name", "last_name", "academic_year", "section", "gender",
        "date_of_birth", "student_mobile", "official_email", "aadhaar_number", "abc_id", "mode_of_admission",
        "residency", "bus_number", "boarding_point", "hostel_block", "room_number", "scholarship_status",
        "scholarship_name", "scholarship_amount", "tenth_marks", "tenth_board", "tenth_state",
        "twelfth_percentage", "twelfth_total", "twelfth_cutoff", "twelfth_board", "twelfth_school_city",
        "twelfth_study_state", "father_name", "father_mobile", "father_occupation", "mother_name",
        "mother_mobile", "mother_occupation", "single_parent_status", "guardian_name", "single_child_status",
        "family_income", "communication_address", "plan_after_degree", "dream_company"
    ));

    private static final List<Field> FIELDS;
    private static final Map<String, Field> BY_KEY;

    static {
        List<Field> fields = new ArrayList<>();
        add(fields, "register_no", "Register Number", "text", "Identity", true);
        add(fields, "admission_no", "Admission Number", "text", "Identity", false);
        add(fields, "first_name", "First Name", "text", "Identity", true);
        add(fields, "last_name", "Last Name", "text", "Identity", false);
        add(fields, "academic_year", "Academic Year", "text", "Identity", false);
        add(fields, "section", "Section", "text", "Identity", false);
        add(fields, "gender", "Gender", "text", "Identity", false);
        add(fields, "date_of_birth", "Date of Birth", "date", "Identity", false);
        add(fields, "student_mobile", "Student Mobile", "tel", "Contact Details", false);
        add(fields, "official_email", "Official Email", "email", "Contact Details", false);
        add(fields, "mode_of_admission", "Mode of Admission", "text", "Academic Journey", false);
        add(fields, "plan_after_degree", "Plan After Degree", "text", "Academic Journey", false);
        add(fields, "dream_company", "Dream Company", "text", "Academic Journey", false);
        add(fields, "residency", "Day Scholar / Hosteller", "text", "Residence and Scholarship", false);
        add(fields, "bus_number", "Bus Number", "text", "Residence and Scholarship", false);
        add(fields, "boarding_point", "Boarding Point", "text", "Residence and Scholarship", false);
        add(fields, "hostel_block", "Hostel Block", "text", "Residence and Scholarship", false);
        add(fields, "room_number", "Room Number", "text", "Residence and Scholarship", false);
        add(fields, "scholarship_status", "Scholarship Status", "text", "Residence and Scholarship", false);
        add(fields, "scholarship_name", "Scholarship Name", "text", "Residence and Scholarship", false);
        add(fields, "scholarship_amount", "Scholarship Amount", "number", "Residence and Scholarship", false);
        add(fields, "tenth_marks", "10th Marks", "text", "School Record", false);
        add(fields, "tenth_board", "10th Board", "text", "School Record", false);
        add(fields, "tenth_state", "10th State", "text", "School Record", false);
        add(fields, "twelfth_percentage", "12th Percentage", "number", "School Record", false);
        add(fields, "twelfth_total", "12th Total", "text", "School Record", false);
        add(fields, "twelfth_cutoff", "12th Cutoff", "text", "School Record", false);
        add(fields, "twelfth_board", "12th Board", "text", "School Record", false);
        add(fields, "twelfth_school_city", "12th School and City", "text", "School Record", false);
        add(fields, "twelfth_study_state", "State of 12th Study", "text", "School Record", false);
        add(fields, "father_name", "Father Name", "text", "Family Contacts", false);
        add(fields, "father_mobile", "Father Mobile", "tel", "Family Contacts", false);
        add(fields, "father_occupation", "Father Occupation", "text", "Family Contacts", false);
        add(fields, "mother_name", "Mother Name", "text", "Family Contacts", false);
        add(fields, "mother_mobile", "Mother Mobile", "tel", "Family Contacts", false);
        add(fields, "mother_occupation", "Mother Occupation", "text", "Family Contacts", false);
        add(fields, "single_parent_status", "Single Parent Status", "text", "Family Contacts", false);
        add(fields, "guardian_name", "Parent / Guardian Name", "text", "Family Contacts", false);
        add(fields, "single_child_status", "Single Child Status", "text", "Family Contacts", false);
        add(fields, "family_income", "Family Income", "text", "Family Contacts", false);
        add(fields, "communication_address", "Communication Address", "textarea", "Family Contacts", false);
        add(fields, "aadhaar_number", "Aadhaar Number", "text", "Secure Identifiers", false);
        add(fields, "abc_id", "ABC ID", "text", "Secure Identifiers", false);
        FIELDS = Collections.unmodifiableList(fields);
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (Field field : fields) {
            byKey.put(field.key, field);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    private StudentFieldCatalog() {
    }

    private static void add(List<Field> fields, String key, String label, String type, String group, boolean required) {
        fields.add(new Field(key, label, type, group, required));
    }

    public static List<Map<String, Object>> metadata() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Field field : FIELDS) {
            result.add(field.toMap());
        }
        return result;
    }

    public static Field get(String key) {
        return BY_KEY.get(key);
    }

    public static List<String> keys() {
        return KEYS;
    }

    public static final class Field {
        public final String key;
        public final String label;
        public final String type;
        public final String group;
        public final boolean required;

        private Field(String key, String label, String type, String group, boolean required) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.group = group;
            this.required = required;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", key);
            value.put("label", label);
            value.put("type", type);
            value.put("group", group);
            value.put("required", required);
            return value;
        }
    }
}
