CREATE TABLE IF NOT EXISTS students (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    register_no VARCHAR(100) NOT NULL UNIQUE,
    admission_no VARCHAR(150),
    first_name VARCHAR(200) NOT NULL,
    last_name VARCHAR(200),
    academic_year VARCHAR(50),
    section VARCHAR(50),
    gender VARCHAR(50),
    date_of_birth VARCHAR(50),
    student_mobile VARCHAR(100),
    official_email VARCHAR(254),
    aadhaar_number VARCHAR(100),
    abc_id VARCHAR(100),
    mode_of_admission VARCHAR(150),
    residency VARCHAR(200),
    bus_number VARCHAR(100),
    boarding_point VARCHAR(500),
    hostel_block VARCHAR(100),
    room_number VARCHAR(100),
    scholarship_status VARCHAR(100),
    scholarship_name VARCHAR(300),
    scholarship_amount VARCHAR(100),
    tenth_marks VARCHAR(100),
    tenth_board VARCHAR(200),
    tenth_state VARCHAR(200),
    twelfth_percentage VARCHAR(100),
    twelfth_total VARCHAR(100),
    twelfth_cutoff VARCHAR(100),
    twelfth_board VARCHAR(200),
    twelfth_school_city VARCHAR(500),
    twelfth_study_state VARCHAR(200),
    father_name VARCHAR(200),
    father_mobile VARCHAR(100),
    father_occupation VARCHAR(500),
    mother_name VARCHAR(200),
    mother_mobile VARCHAR(100),
    mother_occupation VARCHAR(500),
    single_parent_status VARCHAR(100),
    guardian_name VARCHAR(200),
    single_child_status VARCHAR(100),
    family_income VARCHAR(100),
    communication_address TEXT,
    plan_after_degree VARCHAR(500),
    dream_company VARCHAR(300),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS custom_fields (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    field_key VARCHAR(100) NOT NULL UNIQUE,
    label VARCHAR(200) NOT NULL,
    field_type VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS student_custom_values (
    student_id BIGINT NOT NULL,
    field_key VARCHAR(100) NOT NULL,
    field_value TEXT,
    PRIMARY KEY (student_id, field_key),
    CONSTRAINT fk_custom_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_custom_field FOREIGN KEY (field_key) REFERENCES custom_fields(field_key) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS attendance_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attendance_date DATE NOT NULL,
    subject_name VARCHAR(200) NOT NULL,
    hour_or_period VARCHAR(100) NOT NULL,
    faculty_name VARCHAR(200),
    section VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS attendance_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    remarks VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attendance_session_student UNIQUE (session_id, student_id),
    CONSTRAINT fk_attendance_session FOREIGN KEY (session_id) REFERENCES attendance_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS subjects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_code VARCHAR(60) NOT NULL,
    subject_name VARCHAR(200) NOT NULL,
    semester VARCHAR(50) NOT NULL,
    academic_year VARCHAR(50) NOT NULL,
    section VARCHAR(50) NOT NULL,
    max_internal_marks DECIMAL(8,2) NOT NULL DEFAULT 0,
    max_external_marks DECIMAL(8,2) NOT NULL DEFAULT 0,
    max_total_marks DECIMAL(8,2) NOT NULL,
    CONSTRAINT uq_subject_code_scope UNIQUE (subject_code, semester, academic_year, section)
);

CREATE TABLE IF NOT EXISTS assessments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    assessment_name VARCHAR(200) NOT NULL,
    assessment_type VARCHAR(50) NOT NULL,
    assessment_date DATE,
    max_marks DECIMAL(8,2) NOT NULL,
    weightage DECIMAL(8,2) NOT NULL DEFAULT 100,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_assessment_subject FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS student_marks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    marks_obtained DECIMAL(8,2) NOT NULL,
    remarks VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_marks_student_assessment UNIQUE (student_id, assessment_id),
    CONSTRAINT fk_marks_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_marks_assessment FOREIGN KEY (assessment_id) REFERENCES assessments(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS certificates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_name VARCHAR(200) NOT NULL UNIQUE,
    issuing_organization VARCHAR(200),
    description VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS student_certificates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    certificate_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    completion_date DATE,
    certificate_number VARCHAR(200),
    proof_file_path VARCHAR(1000),
    remarks VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_student_certificate UNIQUE (student_id, certificate_id),
    CONSTRAINT fk_student_certificate_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    CONSTRAINT fk_student_certificate_certificate FOREIGN KEY (certificate_id) REFERENCES certificates(id) ON DELETE CASCADE
);
