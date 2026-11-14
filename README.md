# StudSRM

StudSRM is a locally hosted student-record dashboard for III CSE – Section D. It provides a searchable directory, editable student profiles, Excel refresh imports, configurable custom fields, attendance tracking, marks management, and filtered report exports. Sensitive details such as Aadhaar, family information, and communication addresses are returned only for an individual profile, never in the directory response.

The app is built with HTML, CSS, vanilla JavaScript, Java 8+, Spring Boot, Spring JDBC, H2, Apache POI, PDFBox, and Maven. Student data stays in the local application and database; the app does not send records to third-party services. The supplied workbook's first sheet was converted into a tabular seed file. Embedded student photos are not included.

## Requirements

- Java 8 or later
- Maven 3.6 or later

## Run locally

From the project directory:

```bash
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080).

On the first run, StudSRM seeds an empty database from `src/main/resources/seed/students.json` when that file is present. The supplied seed contains private student information and is intentionally excluded from this repository; without a local seed file, the app starts with an empty database. StudSRM does not reseed a database that already contains student records.

## Excel import

Use **Import data** in the dashboard to select an `.xlsx` or `.xls` workbook. StudSRM reads the first worksheet, maps recognized header names to student fields, skips empty or incomplete rows, and ignores unsupported columns and embedded images. Excel date cells are stored as `DD/MM/YYYY`.

Register Number is the unique identifier. A new register number creates a record; a matching one refreshes the existing record instead of creating a duplicate. Blank cells in an imported row do not erase existing values. The import summary reports inserted, refreshed, and skipped rows.

## Attendance

Open **Attendance** in the sidebar to create a dated session by subject, period, faculty, and section. The session roster includes every student in that section and defaults everyone to **Present**; statuses can be changed to **Absent**, **Late**, **On Duty**, or **Leave**, with optional remarks. Bulk saves are transactional, and a student can have only one record in each session. Existing sessions can be edited or deleted.

The attendance percentage is `(Present + Late + On Duty) / all recorded sessions × 100`; **Leave is not counted as present**. The dashboard identifies critical shortage below 75%, needs attention from 75–84%, and good attendance at 85% or more. Session history supports date-range, section, subject, student, and status filters.

## Marks and academic performance

Create subjects scoped to a section, semester, and academic year, then create assessments and load the section roster. Marks can be saved in bulk, edited individually, and left blank when not yet entered. Values must be non-negative and cannot exceed the assessment maximum. Deleting a subject or assessment requires confirmation in the UI and removes its dependent records.

Percentages and grades use the default bands **O (90+)**, **A+ (80–89)**, **A (70–79)**, **B+ (60–69)**, **B (50–59)**, **C (40–49)**, and **Fail (below 40)**. Overall and subject performance calculations apply each assessment's configured weightage. Performance indicators use green at 75%+, amber at 50–74%, and red below 50%. The **Academic performance** tab in a student profile combines attendance, assessment results, subject totals, grades, remarks, section rank, and a simple marks trend. The marks API is structured for later workbook-import support; marks import is not part of this release.

## Reports and privacy

The **Reports** page previews filtered Student, Attendance, or Marks reports before download as `.xlsx`, `.csv`, or `.pdf`. Exports include a title, generation date, selected filters, and record count. Student report types include the directory, scholarship students, hostellers, day scholars, parent contacts, and placement interest. Attendance and marks reports include daily/subject/student/shortage/monthly/section reports and assessment/progress/performance/below-pass/rank/semester reports.

Student exports omit private identifiers and family information by default. To include them, select **Include sensitive fields**, accept the warning for the preview, and separately confirm again at download time. Parent-contact exports require the same confirmation. Store sensitive exports securely and share them only with authorized people.

## Smart Query and certificates

Use **Smart Query** to search with simple questions such as “Students interested in TCS”, “Hostellers with scholarships”, “Attendance below 75%”, “Students with marks below 50% in Java”, or “How many students completed the Globalization Certificate?”. Certificate searches can be narrowed to one student with a registration number, for example: “Show students pursuing the Globalization Certificate with registration number 123456”. Registration numbers are matched exactly. The local rule-based parser recognizes career/company interests, certificate status, attendance and marks thresholds, residence, scholarship, gender, section, state, admission mode, and registration number. It translates recognized terms into parameterized JDBC queries; query text is never executed as SQL. Results exclude Aadhaar, ABC ID, home address, family income, and parent contact details. Student mobile numbers appear only for matching career and general student queries.

Smart Query results can be exported as Excel, CSV, or PDF. StudSRM preloads the supplied certification catalog on startup without duplicating entries or changing student statuses; use the certificate catalog on the Smart Query page to add more certificates and issuing organizations. In a saved student profile, open **Certificates** to set a certificate status, completion date, certificate number, and remarks. Removing a certificate from the catalog also removes its associated student statuses.

## Local database

H2 stores records in `./data/studsrm.mv.db` relative to the directory where the application is run. The `data/` directory is created automatically. The H2 web console is available at [http://localhost:8080/h2-console](http://localhost:8080/h2-console); use JDBC URL `jdbc:h2:file:./data/studsrm`, user `sa`, and a blank password.

Keep the database and any local seed file private: they may contain student information. Do not publish them or expose the H2 console beyond a trusted local environment.

## Moving to MySQL

For a shared deployment, use a secured MySQL instance and a private network. Replace the H2 runtime dependency in `pom.xml` with the MySQL Connector/J runtime dependency, then configure the connection using environment variables:

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/studsrm?useSSL=true&serverTimezone=UTC' \
SPRING_DATASOURCE_USERNAME='studsrm_app' \
SPRING_DATASOURCE_PASSWORD='your-local-secret' \
SPRING_DATASOURCE_DRIVER='com.mysql.cj.jdbc.Driver' \
mvn spring-boot:run
```

Create the `studsrm` database and a least-privilege application user first. Review and apply `schema.sql` to the target MySQL version before migration, back up the local H2 file, and migrate records over a secured connection. For deployment, also add authentication, HTTPS, access controls, encrypted backups, and a retention policy before making the application reachable by other users.

## API overview

- `GET /api/students` — searchable, filterable directory fields only
- `GET /api/students/{id}` — full student profile and custom values
- `POST /api/students`, `PUT /api/students/{id}`, `DELETE /api/students/{id}`
- `GET /api/metadata` — class counts, sections, and standard profile fields
- `GET /api/fields`, `POST /api/fields`, `DELETE /api/fields/{key}`
- `POST /api/import` — multipart Excel upload (`file`)
- `/api/attendance/sessions` — list, create, retrieve, update, and delete sessions; `/api/attendance/student/{studentId}`, `/api/attendance/shortage`, `/api/attendance/dashboard`, and `/api/attendance/stats`
- `/api/subjects` and `/api/assessments` — create, list, update, and delete academic subjects and assessments
- `GET /api/marks`, `POST /api/marks/bulk`, `PUT /api/marks/{id}`, `GET /api/marks/student/{studentId}`, and `GET /api/marks/performance`
- `POST /api/reports/{students|attendance|marks}/preview` — filtered preview; `POST /api/reports/{students|attendance|marks}?format=xlsx|csv|pdf` — export
- `POST /api/smart-query`, `GET /api/smart-query/suggestions`, and `POST /api/smart-query/export?format=xlsx|csv|pdf`
- `GET|POST /api/certificates`, `PUT|DELETE /api/certificates/{id}`, and `/api/students/{studentId}/certificates` for student certificate statuses

Directory filters are `query`, `section`, `residency`, and `scholarship`.
