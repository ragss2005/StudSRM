"use strict";

const state = {
    metadata: null,
    customFields: [],
    activeDialog: null,
    returnFocus: null,
    searchTimer: null,
    view: "directory",
    academicStudents: [],
    subjects: [],
    assessments: [],
    attendanceRoster: [],
    marksRoster: [],
    reportData: null,
    currentStudentId: null,
    profileTab: "details",
    smartQueryResult: null,
    certificates: [],
    studentCertificates: []
};

const elements = {
    rows: document.getElementById("student-rows"),
    search: document.getElementById("search"),
    section: document.getElementById("section-filter"),
    residency: document.getElementById("residency-filter"),
    scholarship: document.getElementById("scholarship-filter"),
    profileDialog: document.getElementById("profile-dialog"),
    profileContent: document.getElementById("profile-content"),
    studentForm: document.getElementById("student-form"),
    fieldsDialog: document.getElementById("fields-dialog"),
    fieldForm: document.getElementById("field-form"),
    customFieldList: document.getElementById("custom-field-list"),
    importFile: document.getElementById("import-file"),
    attendanceDialog: document.getElementById("attendance-dialog"),
    attendanceForm: document.getElementById("attendance-form"),
    reportForm: document.getElementById("report-form")
};

async function api(url, options = {}) {
    const response = await fetch(url, options);
    if (response.status === 204) return null;
    const contentType = response.headers.get("content-type") || "";
    const body = contentType.includes("application/json") ? await response.json() : null;
    if (!response.ok) throw new Error((body && body.error) || `Request failed (${response.status}).`);
    return body;
}

function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, character => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
    })[character]);
}

function initials(student) {
    const first = (student.first_name || "").trim();
    const last = (student.last_name || "").trim();
    return ((first[0] || "") + (last[0] || "")).toUpperCase() || "?";
}

function isHosteller(value) {
    return String(value || "").toLowerCase().includes("hostel");
}

function hasScholarship(value) {
    return ["yes", "y", "true"].includes(String(value || "").trim().toLowerCase());
}

function showToast(message, kind = "success") {
    const toast = document.createElement("div");
    toast.className = `toast${kind === "error" ? " error" : kind === "info" ? " info" : ""}`;
    toast.textContent = message;
    elements.toastRegion = document.getElementById("toast-region");
    elements.toastRegion.appendChild(toast);
    window.setTimeout(() => toast.remove(), 4300);
}

async function loadMetadata() {
    const metadata = await api("/api/metadata");
    state.metadata = metadata;
    document.getElementById("total-students").textContent = metadata.totalStudents;
    document.getElementById("total-hostellers").textContent = metadata.hostellers;
    document.getElementById("total-scholarship").textContent = metadata.scholarshipStudents;
    document.getElementById("total-sections").textContent = metadata.sectionCount;

    const currentSection = elements.section.value;
    elements.section.innerHTML = `<option value="">All sections</option>${(metadata.sections || []).map(section =>
        `<option value="${escapeHtml(section)}">Section ${escapeHtml(section)}</option>`).join("")}`;
    elements.section.value = currentSection;
    document.querySelectorAll(".academic-section").forEach(select => {
        const selected = select.value;
        const first = select.options.length && select.options[0].value === "" ? select.options[0].outerHTML : `<option value="">All sections</option>`;
        select.innerHTML = first + (metadata.sections || []).map(section =>
            `<option value="${escapeHtml(section)}">Section ${escapeHtml(section)}</option>`).join("");
        select.value = selected;
    });
}

function queryString() {
    const params = new URLSearchParams();
    if (elements.search.value.trim()) params.set("query", elements.search.value.trim());
    if (elements.section.value) params.set("section", elements.section.value);
    if (elements.residency.value) params.set("residency", elements.residency.value);
    if (elements.scholarship.value) params.set("scholarship", elements.scholarship.value);
    return params.toString();
}

async function loadStudents() {
    elements.rows.innerHTML = `<tr><td class="table-message" colspan="6"><span class="loader"></span> Loading student directory…</td></tr>`;
    try {
        const query = queryString();
        const students = await api(`/api/students${query ? `?${query}` : ""}`);
        renderStudents(students);
    } catch (error) {
        elements.rows.innerHTML = `<tr><td class="table-message" colspan="6">${escapeHtml(error.message || "Unable to load students.")}</td></tr>`;
        document.getElementById("directory-count").textContent = "!";
        document.getElementById("table-footer-text").textContent = "Could not load student records";
    }
}

function renderStudents(students) {
    const count = students.length;
    document.getElementById("directory-count").textContent = count;
    document.getElementById("table-footer-text").textContent = `${count} ${count === 1 ? "student" : "students"} shown`;
    if (count === 0) {
        elements.rows.innerHTML = `<tr><td colspan="6"><div class="empty-state"><strong>No students found</strong><span>Try changing your search or filters, or add a new student record.</span></div></td></tr>`;
        return;
    }
    elements.rows.innerHTML = students.map((student, index) => {
        const name = [student.first_name, student.last_name].filter(Boolean).join(" ");
        const section = student.section ? `Section ${escapeHtml(student.section)}` : "Section —";
        const year = student.academic_year ? escapeHtml(student.academic_year) : "III";
        const residence = !student.residency
            ? `<span class="status-badge residence-unknown">Not specified</span>`
            : isHosteller(student.residency)
                ? `<span class="status-badge residence-hosteller">Hosteller</span>`
                : `<span class="status-badge residence-day">Day Scholar</span>`;
        const scholarship = hasScholarship(student.scholarship_status)
            ? `<span class="status-badge scholarship-yes">Scholarship</span>`
            : `<span class="status-badge scholarship-no">No scholarship</span>`;
        return `<tr>
            <td><div class="student-cell"><span class="avatar tone-${index % 5}">${escapeHtml(initials(student))}</span><span>
                <span class="student-name">${escapeHtml(name || "Student")}</span>
                <span class="student-email">${escapeHtml(student.official_email || "No official email")}</span>
            </span></div></td>
            <td><span class="register-number">${escapeHtml(student.register_no)}</span></td>
            <td><span class="class-label">${year} CSE · ${section}</span></td>
            <td>${residence}</td>
            <td>${scholarship}</td>
            <td><button class="open-profile" type="button" data-student-id="${Number(student.id)}" aria-label="Open profile for ${escapeHtml(name)}">View profile <span aria-hidden="true">→</span></button></td>
        </tr>`;
    }).join("");
}

async function refreshDashboard(showFeedback = false) {
    try {
        await Promise.all([loadMetadata(), loadStudents()]);
        if (showFeedback) showToast("Directory refreshed.", "info");
    } catch (error) {
        if (showFeedback) showToast(error.message || "Could not refresh the directory.", "error");
    }
}

function openDialog(dialog) {
    state.returnFocus = document.activeElement;
    state.activeDialog = dialog;
    dialog.hidden = false;
    document.body.style.overflow = "hidden";
    const panel = dialog.querySelector('[role="dialog"]');
    window.setTimeout(() => panel.focus(), 0);
}

function closeDialog(dialog) {
    if (!dialog || dialog.hidden) return;
    dialog.hidden = true;
    state.activeDialog = null;
    document.body.style.overflow = "";
    if (state.returnFocus && typeof state.returnFocus.focus === "function") state.returnFocus.focus();
}

function formatDateForInput(value) {
    const match = String(value || "").match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    if (match) return `${match[3]}-${match[2].padStart(2, "0")}-${match[1].padStart(2, "0")}`;
    return String(value || "");
}

function formatDateForApi(value) {
    const match = String(value || "").match(/^(\d{4})-(\d{2})-(\d{2})$/);
    return match ? `${match[3]}/${match[2]}/${match[1]}` : String(value || "");
}

function createFieldControl(field, value = "", custom = false) {
    const id = `field-${field.key}`;
    const label = `${escapeHtml(field.label)}${field.required ? '<span class="required-mark" aria-hidden="true">*</span>' : ""}`;
    const required = field.required ? " required" : "";
    const type = field.type === "tel" || field.type === "email" || field.type === "number" || field.type === "date"
        ? field.type : "text";
    const controlValue = field.type === "date" ? formatDateForInput(value) : String(value || "");
    let control;
    if (field.type === "textarea") {
        control = `<textarea id="${id}" name="${escapeHtml(field.key)}" ${required} maxlength="4000" placeholder="Add ${escapeHtml(field.label.toLowerCase())}">${escapeHtml(controlValue)}</textarea>`;
    } else {
        control = `<input id="${id}" name="${escapeHtml(field.key)}" type="${type}" value="${escapeHtml(controlValue)}"${required}${field.type === "number" ? ' step="any"' : ""}${custom ? ' maxlength="1000"' : ""}>`;
    }
    const fullWidth = field.type === "textarea" ? " full-width" : "";
    return `<label class="field-control${fullWidth}" for="${id}"><span>${label}</span>${control}</label>`;
}

function renderProfile(student) {
    const groups = new Map();
    for (const field of (state.metadata && state.metadata.fields) || []) {
        if (!groups.has(field.group)) groups.set(field.group, []);
        groups.get(field.group).push(createFieldControl(field, student[field.key]));
    }
    const customValues = student.customFields || {};
    for (const field of state.customFields) {
        if (!groups.has("Additional Custom Fields")) groups.set("Additional Custom Fields", []);
        groups.get("Additional Custom Fields").push(createFieldControl(
            { key: field.key, label: field.label, type: field.type, required: false },
            customValues[field.key],
            true
        ));
    }
    elements.profileContent.innerHTML = Array.from(groups.entries()).map(([group, controls]) =>
        `<section class="field-group"><h3>${escapeHtml(group)}</h3><div class="field-grid">${controls.join("")}</div></section>`
    ).join("");
}

async function openStudentProfile(id = null) {
    elements.profileContent.innerHTML = `<div class="loading-panel"><span class="loader"></span> Preparing student profile…</div>`;
    document.getElementById("profile-title").textContent = id ? "Student details" : "Add a student";
    document.getElementById("profile-mode").textContent = id ? "STUDENT PROFILE" : "NEW STUDENT RECORD";
    document.getElementById("profile-subtitle").textContent = id
        ? "Review and update this student's information."
        : "Add a student to the III CSE directory.";
    document.getElementById("save-student").textContent = id ? "Save changes" : "Save student";
    document.getElementById("delete-student").hidden = !id;
    elements.studentForm.dataset.studentId = id || "";
    state.currentStudentId = id;
    state.profileTab = "details";
    document.getElementById("profile-content").hidden = false;
    document.getElementById("profile-performance").hidden = true;
    document.getElementById("profile-tab-details").classList.add("active");
    document.getElementById("profile-tab-details").setAttribute("aria-selected", "true");
    document.getElementById("profile-tab-performance").classList.remove("active");
    document.getElementById("profile-tab-performance").setAttribute("aria-selected", "false");
    document.getElementById("profile-tab-performance").disabled = !id;
    document.getElementById("profile-tab-certificates").classList.remove("active");
    document.getElementById("profile-tab-certificates").setAttribute("aria-selected", "false");
    document.getElementById("profile-tab-certificates").disabled = !id;
    document.getElementById("profile-certificates").hidden = true;
    document.getElementById("save-student").hidden = false;
    document.getElementById("delete-student").hidden = !id;
    openDialog(elements.profileDialog);
    try {
        const [student, customFields] = await Promise.all([
            id ? api(`/api/students/${id}`) : Promise.resolve({ customFields: {} }),
            api("/api/fields")
        ]);
        state.customFields = customFields;
        renderProfile(student);
    } catch (error) {
        elements.profileContent.innerHTML = `<div class="loading-panel">${escapeHtml(error.message || "Could not open this profile.")}</div>`;
        showToast(error.message || "Could not open this profile.", "error");
    }
}

function serializeStudentForm(form) {
    const result = {};
    const customValues = {};
    for (const [key, value] of new FormData(form).entries()) {
        const field = (state.metadata.fields || []).find(candidate => candidate.key === key);
        const custom = state.customFields.find(candidate => candidate.key === key);
        const normalized = field && field.type === "date" ? formatDateForApi(value) : value;
        if (custom) customValues[key] = normalized;
        else result[key] = normalized;
    }
    result.customFields = customValues;
    return result;
}

async function saveStudent(event) {
    event.preventDefault();
    const button = document.getElementById("save-student");
    if (!elements.studentForm.reportValidity()) return;
    const id = elements.studentForm.dataset.studentId;
    button.disabled = true;
    button.innerHTML = `<span class="loader"></span> Saving…`;
    try {
        await api(id ? `/api/students/${id}` : "/api/students", {
            method: id ? "PUT" : "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(serializeStudentForm(elements.studentForm))
        });
        closeDialog(elements.profileDialog);
        await refreshDashboard();
        await loadAcademicReferenceData();
        showToast(id ? "Student details saved." : "Student added to the directory.");
    } catch (error) {
        showToast(error.message || "Could not save this student.", "error");
    } finally {
        button.disabled = false;
        button.textContent = id ? "Save changes" : "Save student";
    }
}

async function deleteStudent() {
    const id = elements.studentForm.dataset.studentId;
    const name = document.getElementById("profile-title").textContent;
    if (!id || !window.confirm(`Delete ${name}? This will permanently remove the student record.`)) return;
    const button = document.getElementById("delete-student");
    button.disabled = true;
    try {
        await api(`/api/students/${id}`, { method: "DELETE" });
        closeDialog(elements.profileDialog);
        await refreshDashboard();
        showToast("Student record deleted.", "info");
    } catch (error) {
        showToast(error.message || "Could not delete this student.", "error");
    } finally {
        button.disabled = false;
    }
}

async function openCustomFields() {
    openDialog(elements.fieldsDialog);
    await loadCustomFields();
}

async function loadCustomFields() {
    elements.customFieldList.innerHTML = `<div class="loading-panel"><span class="loader"></span> Loading fields…</div>`;
    try {
        state.customFields = await api("/api/fields");
        document.getElementById("field-count").textContent = state.customFields.length;
        if (state.customFields.length === 0) {
            elements.customFieldList.innerHTML = `<div class="fields-empty">No custom fields yet. Add one above to capture class-specific information.</div>`;
            return;
        }
        elements.customFieldList.innerHTML = state.customFields.map(field => `
            <div class="custom-field-item">
                <span class="custom-field-symbol" aria-hidden="true">${field.type === "date" ? "◷" : field.type === "number" ? "#" : "Aa"}</span>
                <span class="custom-field-copy"><strong>${escapeHtml(field.label)}</strong><span>${escapeHtml(field.type)}</span></span>
                <button class="remove-field" type="button" data-field-key="${escapeHtml(field.key)}" aria-label="Remove ${escapeHtml(field.label)}">Remove</button>
            </div>`).join("");
    } catch (error) {
        elements.customFieldList.innerHTML = `<div class="fields-empty">${escapeHtml(error.message || "Could not load custom fields.")}</div>`;
    }
}

async function addCustomField(event) {
    event.preventDefault();
    const form = elements.fieldForm;
    const button = form.querySelector('button[type="submit"]');
    const data = new FormData(form);
    button.disabled = true;
    try {
        await api("/api/fields", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ label: data.get("label"), type: data.get("type") })
        });
        form.reset();
        await loadCustomFields();
        showToast("Custom field added to student profiles.");
    } catch (error) {
        showToast(error.message || "Could not add the custom field.", "error");
    } finally {
        button.disabled = false;
    }
}

async function removeCustomField(key) {
    const field = state.customFields.find(candidate => candidate.key === key);
    if (!field || !window.confirm(`Remove "${field.label}" from all student profiles? Stored values for this field will also be deleted.`)) return;
    try {
        await api(`/api/fields/${encodeURIComponent(key)}`, { method: "DELETE" });
        await loadCustomFields();
        showToast("Custom field removed.", "info");
    } catch (error) {
        showToast(error.message || "Could not remove the custom field.", "error");
    }
}

async function importWorkbook(file) {
    const form = new FormData();
    form.append("file", file);
    showToast(`Importing ${file.name}…`, "info");
    try {
        const result = await api("/api/import", { method: "POST", body: form });
        await refreshDashboard();
        showToast(result.message, "success");
    } catch (error) {
        showToast(error.message || "Could not import that workbook.", "error");
    } finally {
        elements.importFile.value = "";
    }
}

const reportOptions = {
    students: [
        ["directory", "Complete student directory"], ["scholarship", "Scholarship students"],
        ["hostellers", "Hostellers"], ["day-scholars", "Day scholars"],
        ["parent-contacts", "Parent contact list"], ["placement-interest", "Placement interest"]
    ],
    attendance: [
        ["daily", "Daily attendance sheet"], ["subject", "Subject-wise attendance"],
        ["student-history", "Student attendance history"], ["shortage", "Attendance shortage list"],
        ["monthly-summary", "Monthly attendance summary"], ["section-summary", "Section attendance report"]
    ],
    marks: [
        ["assessment", "Assessment marks sheet"], ["subject", "Subject-wise marks"],
        ["student-progress", "Student progress report"], ["class-performance", "Class performance"],
        ["below-pass", "Students below pass mark"], ["rank-list", "Rank list"],
        ["semester-summary", "Semester summary"]
    ]
};

function moduleFilters(prefix) {
    const filters = {};
    const from = document.getElementById(`${prefix}-from`);
    const to = document.getElementById(`${prefix}-to`);
    const section = document.getElementById(`${prefix}-section`);
    if (from && from.value) filters.startDate = from.value;
    if (to && to.value) filters.endDate = to.value;
    if (section && section.value) filters.section = section.value;
    return filters;
}

function selectFilter(selectors, name, emptyLabel, values, labelBuilder) {
    selectors.forEach(selector => {
        const element = document.querySelector(selector);
        if (!element) return;
        const selected = element.value;
        element.innerHTML = `<option value="">${escapeHtml(emptyLabel)}</option>` + values.map(value => {
            const id = value.id;
            return `<option value="${escapeHtml(id)}">${escapeHtml(labelBuilder(value))}</option>`;
        }).join("");
        element.value = selected;
    });
}

async function loadAcademicReferenceData() {
    try {
        const [students, subjects, types] = await Promise.all([
            api("/api/students"), api("/api/subjects"), api("/api/assessment-types")
        ]);
        state.academicStudents = students;
        state.subjects = subjects;
        selectFilter(["#attendance-student-filter", "#marks-student", "#report-form .student-options"],
            "student", "All students", students, student => `${student.first_name} ${student.last_name || ""} · ${student.register_no}`);
        selectFilter(["#assessment-subject"], "subject", "Choose subject", subjects,
            subject => `${subject.subject_code} · ${subject.subject_name} · ${subject.section}`);
        selectFilter(["#marks-subject", "#report-subject"], "subject", "All subjects", subjects,
            subject => `${subject.subject_code} · ${subject.subject_name}`);
        document.getElementById("assessment-type").innerHTML = [
            `<option value="">Choose type</option>`,
            ...types.map(type => `<option value="${escapeHtml(type)}">${escapeHtml(type)}</option>`)
        ].join("");
        await loadAssessments();
    } catch (error) {
        showToast(error.message || "Could not load academic subjects and students.", "error");
    }
}

async function loadAttendance() {
    const filters = new URLSearchParams();
    const values = {
        startDate: document.getElementById("attendance-from").value,
        endDate: document.getElementById("attendance-to").value,
        section: document.getElementById("attendance-section").value,
        subject: document.getElementById("attendance-subject-filter").value,
        studentId: document.getElementById("attendance-student-filter").value,
        status: document.getElementById("attendance-status-filter").value
    };
    for (const [key, value] of Object.entries(values)) if (value) filters.set(key, value);
    const dashboardFilters = new URLSearchParams();
    if (values.startDate) dashboardFilters.set("startDate", values.startDate);
    if (values.endDate) dashboardFilters.set("endDate", values.endDate);
    if (values.section) dashboardFilters.set("section", values.section);
    try {
        const [sessions, dashboard, stats] = await Promise.all([
            api(`/api/attendance/sessions${filters.size ? `?${filters}` : ""}`),
            api(`/api/attendance/dashboard${dashboardFilters.size ? `?${dashboardFilters}` : ""}`),
            api(`/api/attendance/stats${dashboardFilters.size ? `?${dashboardFilters}` : ""}`)
        ]);
        document.getElementById("attendance-today").textContent = dashboard.todaySessions;
        document.getElementById("attendance-average").textContent = `${dashboard.averageAttendance}%`;
        document.getElementById("attendance-shortage").textContent = dashboard.shortageCount;
        document.getElementById("attendance-students").textContent = stats.filter(student => student.total_sessions > 0).length;
        document.getElementById("attendance-count").textContent = sessions.length;
        document.getElementById("attendance-footer").textContent = `${sessions.length} attendance ${sessions.length === 1 ? "session" : "sessions"}`;
        if (!sessions.length) {
            document.getElementById("attendance-rows").innerHTML = `<tr><td class="table-message" colspan="8">No attendance sessions match these filters. Start by taking attendance.</td></tr>`;
        } else {
            document.getElementById("attendance-rows").innerHTML = sessions.map(session => `
                <tr><td>${escapeHtml(session.attendance_date)}</td><td>${escapeHtml(session.subject_name)}</td><td>${escapeHtml(session.hour_or_period)}</td>
                <td>${escapeHtml(session.section)}</td><td>${escapeHtml(session.faculty_name || "—")}</td>
                <td>${Number(session.attended_count || 0)} / ${Number(session.record_count || 0)}</td>
                <td><span class="performance-badge ${Number(session.attendancePercentage) < 75 ? "performance-red" : Number(session.attendancePercentage) < 85 ? "performance-amber" : "performance-green"}">${Number(session.attendancePercentage)}%</span></td>
                <td class="row-actions"><button class="text-action" type="button" data-edit-attendance="${Number(session.id)}">Edit</button><button class="text-action danger-action" type="button" data-delete-attendance="${Number(session.id)}">Delete</button></td></tr>`).join("");
        }
        renderAttendanceCompact("attendance-highest", dashboard.highestAttendance || [], true);
        renderAttendanceCompact("attendance-lowest", dashboard.shortageStudents || [], false);
    } catch (error) {
        document.getElementById("attendance-rows").innerHTML = `<tr><td class="table-message" colspan="8">${escapeHtml(error.message || "Could not load attendance sessions.")}</td></tr>`;
        showToast(error.message || "Could not load attendance.", "error");
    }
}

function renderAttendanceCompact(elementId, students, high) {
    const target = document.getElementById(elementId);
    if (!students.length) {
        target.innerHTML = `<div class="fields-empty">${high ? "Attendance leaders will appear here after sessions are recorded." : "No attendance shortages."}</div>`;
        return;
    }
    target.innerHTML = students.slice(0, 5).map(student => `
        <div class="compact-row"><div class="compact-student"><strong>${escapeHtml(`${student.first_name} ${student.last_name || ""}`)}</strong><span>${escapeHtml(student.register_no)}</span></div>
        <span class="performance-badge ${high ? "performance-green" : "performance-red"}">${Number(student.attendancePercentage)}%</span></div>`).join("");
}

async function loadAttendanceRoster(existing = null) {
    const section = elements.attendanceForm.elements.section.value;
    if (!section) {
        showToast("Choose a section before loading its students.", "error");
        return;
    }
    const button = document.getElementById("attendance-load-roster");
    button.disabled = true;
    try {
        const students = await api(`/api/students?section=${encodeURIComponent(section)}`);
        const old = new Map((existing || []).map(record => [Number(record.student_id || record.studentId), record]));
        state.attendanceRoster = students.map(student => {
            const prior = old.get(Number(student.id));
            return { studentId: student.id, registerNo: student.register_no,
                name: `${student.first_name} ${student.last_name || ""}`, status: prior && prior.status || "Present",
                remarks: prior && prior.remarks || "" };
        });
        document.getElementById("attendance-roster-count").textContent = `${students.length} students`;
        document.getElementById("attendance-roster").hidden = false;
        renderAttendanceRoster();
    } catch (error) {
        showToast(error.message || "Could not load students in this section.", "error");
    } finally {
        button.disabled = false;
    }
}

function renderAttendanceRoster() {
    const statuses = ["Present", "Absent", "Late", "On Duty", "Leave"];
    document.getElementById("attendance-roster-rows").innerHTML = state.attendanceRoster.map((record, index) => `
        <tr><td>${escapeHtml(record.name)}</td><td>${escapeHtml(record.registerNo)}</td>
        <td><select class="attendance-status status-${record.status.toLowerCase().replace(/\\s+/g, "-")}" data-attendance-status="${index}" aria-label="Attendance status for ${escapeHtml(record.name)}">${statuses.map(status => `<option${status === record.status ? " selected" : ""}>${status}</option>`).join("")}</select></td>
        <td><input class="remark-input" type="text" maxlength="1000" data-attendance-remark="${index}" value="${escapeHtml(record.remarks)}" aria-label="Attendance remark for ${escapeHtml(record.name)}"></td></tr>`).join("");
}

async function openAttendanceEditor(sessionId = null) {
    elements.attendanceForm.reset();
    elements.attendanceForm.dataset.sessionId = sessionId || "";
    document.getElementById("attendance-dialog-title").textContent = sessionId ? "Edit attendance" : "Take attendance";
    document.getElementById("attendance-roster").hidden = true;
    state.attendanceRoster = [];
    elements.attendanceForm.elements.attendanceDate.value = new Date().toISOString().slice(0, 10);
    elements.attendanceForm.elements.section.value = document.getElementById("attendance-section").value || "";
    openDialog(elements.attendanceDialog);
    if (!sessionId) return;
    try {
        const session = await api(`/api/attendance/sessions/${sessionId}`);
        for (const field of ["attendanceDate", "subjectName", "hourOrPeriod", "facultyName", "section"]) {
            const value = session[{ attendanceDate: "attendance_date", subjectName: "subject_name",
                hourOrPeriod: "hour_or_period", facultyName: "faculty_name", section: "section" }[field]];
            elements.attendanceForm.elements[field].value = value || "";
        }
        await loadAttendanceRoster(session.records);
    } catch (error) {
        closeDialog(elements.attendanceDialog);
        showToast(error.message || "Could not open this attendance session.", "error");
    }
}

async function saveAttendance(event) {
    event.preventDefault();
    const form = elements.attendanceForm;
    if (!form.reportValidity()) return;
    const records = state.attendanceRoster.map((record, index) => ({
        studentId: record.studentId,
        status: document.querySelector(`[data-attendance-status="${index}"]`).value,
        remarks: document.querySelector(`[data-attendance-remark="${index}"]`).value
    }));
    if (records.length) {
        const selectedSectionCount = state.academicStudents.filter(student => student.section === form.elements.section.value).length;
        if (records.length !== selectedSectionCount) {
            showToast("Reload the complete section roster before saving.", "error");
            return;
        }
    }
    const id = form.dataset.sessionId;
    const payload = {
        attendanceDate: form.elements.attendanceDate.value,
        subjectName: form.elements.subjectName.value,
        hourOrPeriod: form.elements.hourOrPeriod.value,
        facultyName: form.elements.facultyName.value,
        section: form.elements.section.value
    };
    if (records.length) payload.records = records;
    const button = document.getElementById("attendance-save");
    button.disabled = true;
    try {
        await api(id ? `/api/attendance/sessions/${id}` : "/api/attendance/sessions", {
            method: id ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        closeDialog(elements.attendanceDialog);
        await loadAttendance();
        showToast(id ? "Attendance session updated." : "Attendance saved for the section.");
    } catch (error) {
        showToast(error.message || "Could not save attendance.", "error");
    } finally {
        button.disabled = false;
    }
}

async function deleteAttendance(id) {
    if (!window.confirm("Delete this attendance session and all its student records?")) return;
    try {
        await api(`/api/attendance/sessions/${id}`, { method: "DELETE" });
        await loadAttendance();
        showToast("Attendance session deleted.", "info");
    } catch (error) {
        showToast(error.message || "Could not delete the attendance session.", "error");
    }
}

async function loadAssessments() {
    try {
        const filters = new URLSearchParams();
        if (document.getElementById("marks-subject").value) filters.set("subjectId", document.getElementById("marks-subject").value);
        if (document.getElementById("marks-section").value) filters.set("section", document.getElementById("marks-section").value);
        if (document.getElementById("marks-semester").value) filters.set("semester", document.getElementById("marks-semester").value);
        if (document.getElementById("marks-year").value) filters.set("academicYear", document.getElementById("marks-year").value);
        state.assessments = await api(`/api/assessments${filters.size ? `?${filters}` : ""}`);
        const select = document.getElementById("marks-assessment");
        const selected = select.value;
        select.innerHTML = `<option value="">All assessments</option>${state.assessments.map(assessment =>
            `<option value="${Number(assessment.id)}">${escapeHtml(`${assessment.subject_code} · ${assessment.assessment_name}`)}</option>`).join("")}`;
        select.value = selected;
        renderAssessmentList();
    } catch (error) {
        showToast(error.message || "Could not load assessments.", "error");
    }
}

function renderSubjectList() {
    const container = document.getElementById("subject-list");
    if (!state.subjects.length) {
        container.innerHTML = `<div class="fields-empty">No subjects yet. Add a subject for this section and semester.</div>`;
        return;
    }
    container.innerHTML = state.subjects.map(subject => `
        <div class="compact-row subject-row"><div class="compact-student"><strong>${escapeHtml(subject.subject_code)} · ${escapeHtml(subject.subject_name)}</strong><span>Section ${escapeHtml(subject.section)} · Semester ${escapeHtml(subject.semester)} · Max ${escapeHtml(subject.max_total_marks)}</span></div>
        <button class="text-action" type="button" data-edit-subject="${Number(subject.id)}">Edit</button><button class="text-action danger-action" type="button" data-delete-subject="${Number(subject.id)}">Delete</button></div>`).join("");
}

function renderAssessmentList() {
    const container = document.getElementById("assessment-list");
    if (!state.assessments.length) {
        container.innerHTML = `<div class="fields-empty">No assessments match the selected subjects.</div>`;
        return;
    }
    container.innerHTML = state.assessments.slice(0, 8).map(assessment => `
        <div class="compact-row subject-row"><div class="compact-student"><strong>${escapeHtml(assessment.assessment_name)}</strong><span>${escapeHtml(assessment.subject_code)} · ${escapeHtml(assessment.assessment_type)} · Max ${escapeHtml(assessment.max_marks)}</span></div>
        <button class="text-action" type="button" data-edit-assessment="${Number(assessment.id)}">Edit</button><button class="text-action danger-action" type="button" data-delete-assessment="${Number(assessment.id)}">Delete</button></div>`).join("");
}

async function submitSubject(event) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!form.reportValidity()) return;
    const data = new FormData(form);
    const payload = Object.fromEntries(data.entries());
    for (const key of ["maxInternalMarks", "maxExternalMarks", "maxTotalMarks"]) payload[key] = Number(payload[key]);
    const id = form.dataset.subjectId;
    try {
        await api(id ? `/api/subjects/${id}` : "/api/subjects", {
            method: id ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        form.reset();
        form.elements.academicYear.value = "2026-27";
        form.elements.maxInternalMarks.value = 40;
        form.elements.maxExternalMarks.value = 60;
        form.elements.maxTotalMarks.value = 100;
        delete form.dataset.subjectId;
        form.querySelector('button[type="submit"]').textContent = "Add subject";
        await loadAcademicReferenceData();
        renderSubjectList();
        showToast(id ? "Subject updated." : "Subject created.");
    } catch (error) {
        showToast(error.message || "Could not save the subject.", "error");
    }
}

async function submitAssessment(event) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!form.reportValidity()) return;
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.subjectId = Number(payload.subjectId);
    payload.maxMarks = Number(payload.maxMarks);
    payload.weightage = Number(payload.weightage || 100);
    const id = form.dataset.assessmentId;
    try {
        await api(id ? `/api/assessments/${id}` : "/api/assessments", {
            method: id ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        form.reset();
        delete form.dataset.assessmentId;
        form.querySelector('button[type="submit"]').textContent = "Add assessment";
        await loadAssessments();
        await loadMarks();
        showToast(id ? "Assessment updated." : "Assessment created.");
    } catch (error) {
        showToast(error.message || "Could not save the assessment.", "error");
    }
}

async function editSubject(id) {
    const subject = state.subjects.find(item => Number(item.id) === id);
    if (!subject) return;
    const name = window.prompt("Subject name", subject.subject_name);
    if (name === null) return;
    const form = document.getElementById("subject-form");
    const values = { subjectCode: subject.subject_code, subjectName: name.trim(), semester: subject.semester,
        academicYear: subject.academic_year, section: subject.section, maxInternalMarks: subject.max_internal_marks,
        maxExternalMarks: subject.max_external_marks, maxTotalMarks: subject.max_total_marks };
    if (!name.trim()) return showToast("Subject name is required.", "error");
    form.dataset.subjectId = id;
    for (const [key, value] of Object.entries(values)) form.elements[key].value = value;
    form.querySelector('button[type="submit"]').textContent = "Save subject";
    form.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function deleteSubject(id) {
    if (!window.confirm("Delete this subject, its assessments and all associated marks?")) return;
    try {
        await api(`/api/subjects/${id}`, { method: "DELETE" });
        await loadAcademicReferenceData();
        renderSubjectList();
        await loadMarks();
        showToast("Subject and its assessments deleted.", "info");
    } catch (error) {
        showToast(error.message || "Could not delete the subject.", "error");
    }
}

async function editAssessment(id) {
    const assessment = state.assessments.find(item => Number(item.id) === id);
    if (!assessment) return;
    const name = window.prompt("Assessment name", assessment.assessment_name);
    if (name === null) return;
    const max = window.prompt("Maximum marks", assessment.max_marks);
    if (max === null) return;
    const payload = { subjectId: Number(assessment.subject_id), assessmentName: name.trim(),
        assessmentType: assessment.assessment_type, assessmentDate: assessment.assessment_date || "",
        maxMarks: Number(max), weightage: Number(assessment.weightage) };
    try {
        await api(`/api/assessments/${id}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
        await loadAssessments();
        await loadMarks();
        showToast("Assessment updated.");
    } catch (error) {
        showToast(error.message || "Could not update the assessment.", "error");
    }
}

async function deleteAssessment(id) {
    if (!window.confirm("Delete this assessment and all recorded student marks?")) return;
    try {
        await api(`/api/assessments/${id}`, { method: "DELETE" });
        await loadAssessments();
        await loadMarks();
        showToast("Assessment deleted.", "info");
    } catch (error) {
        showToast(error.message || "Could not delete the assessment.", "error");
    }
}

async function loadMarks() {
    const filters = new URLSearchParams();
    const values = { academicYear: document.getElementById("marks-year").value,
        semester: document.getElementById("marks-semester").value, section: document.getElementById("marks-section").value,
        subjectId: document.getElementById("marks-subject").value, assessmentId: document.getElementById("marks-assessment").value,
        studentId: document.getElementById("marks-student").value };
    for (const [key, value] of Object.entries(values)) if (value) filters.set(key, value);
    try {
        const [rows, performance] = await Promise.all([
            api(`/api/marks${filters.size ? `?${filters}` : ""}`),
            api(`/api/marks/performance${filters.size ? `?${filters}` : ""}`)
        ]);
        document.getElementById("marks-average").textContent = `${performance.classAverage}%`;
        document.getElementById("marks-highest").textContent = `${performance.highestMark}%`;
        document.getElementById("marks-lowest").textContent = `${performance.lowestMark}%`;
        document.getElementById("marks-support").textContent = performance.studentsNeedingSupport;
        document.getElementById("marks-count").textContent = rows.length;
        document.getElementById("marks-rows").innerHTML = rows.length ? rows.map(row => {
            const pct = row.percentage == null ? null : Number(row.percentage);
            const indicator = pct == null ? "performance-gray" : pct >= 75 ? "performance-green" : pct >= 50 ? "performance-amber" : "performance-red";
            return `<tr><td>${escapeHtml(`${row.first_name} ${row.last_name || ""}`)}<span class="table-secondary">${escapeHtml(row.register_no)}</span></td><td>${escapeHtml(row.subject_code)} · ${escapeHtml(row.subject_name)}</td><td>${escapeHtml(row.assessment_name)}</td>
                <td>${row.missing ? "—" : escapeHtml(row.marks_obtained)}</td><td>${escapeHtml(row.max_marks)}</td><td>${escapeHtml(row.grade || "—")}</td>
                <td>${escapeHtml(row.missing ? "—" : (performance.students.find(student => Number(student.studentId) === Number(row.student_id)) || {}).rank || "—")}</td>
                <td><span class="performance-badge ${indicator}">${row.missing ? "Missing" : pct + "%"}</span>
                ${row.mark_id ? `<button class="text-action mark-edit" type="button" data-edit-mark="${Number(row.mark_id)}">Edit</button><button class="text-action danger-action mark-edit" type="button" data-delete-mark="${Number(row.mark_id)}">Remove</button>` : ""}</td></tr>`;
        }).join("") : `<tr><td class="table-message" colspan="8">No marks or assessments match these filters.</td></tr>`;
        renderSubjectPerformance(performance.subjectPerformance || []);
        renderRecentAssessments(performance.recentAssessments || []);
    } catch (error) {
        document.getElementById("marks-rows").innerHTML = `<tr><td class="table-message" colspan="8">${escapeHtml(error.message || "Could not load marks.")}</td></tr>`;
        showToast(error.message || "Could not load marks.", "error");
    }
}

function renderSubjectPerformance(subjects) {
    const target = document.getElementById("marks-subject-performance");
    target.innerHTML = subjects.length ? subjects.map(subject => `<div class="compact-row"><div class="compact-student"><strong>${escapeHtml(subject.subjectCode)} · ${escapeHtml(subject.subjectName)}</strong><span>${escapeHtml(subject.grade)}</span></div><span class="performance-badge ${Number(subject.percentage) >= 75 ? "performance-green" : Number(subject.percentage) >= 50 ? "performance-amber" : "performance-red"}">${Number(subject.percentage)}%</span></div>`).join("") : `<div class="fields-empty">Subject performance will appear after marks are entered.</div>`;
}

function renderRecentAssessments(assessments) {
    const target = document.getElementById("marks-recent");
    target.innerHTML = assessments.length ? assessments.map(assessment => `<div class="compact-row"><div class="compact-student"><strong>${escapeHtml(assessment.subject_code)} · ${escapeHtml(assessment.assessment_name)}</strong><span>${escapeHtml(assessment.assessment_type)} · ${escapeHtml(assessment.assessment_date || "Date not set")}</span></div><span class="status-badge residence-day">${escapeHtml(assessment.section)}</span></div>`).join("") : `<div class="fields-empty">Recent assessments will appear here.</div>`;
}

async function openMarksRoster() {
    const assessmentId = document.getElementById("marks-assessment").value;
    if (!assessmentId) {
        showToast("Choose an assessment to load its marks roster.", "error");
        return;
    }
    try {
        const rows = await api(`/api/marks?assessmentId=${encodeURIComponent(assessmentId)}`);
        state.marksRoster = rows;
        const assessment = state.assessments.find(item => Number(item.id) === Number(assessmentId));
        document.getElementById("marks-entry-caption").textContent = `${assessment.subject_code} · ${assessment.assessment_name} · Maximum ${assessment.max_marks}`;
        document.getElementById("marks-roster").innerHTML = rows.map((row, index) => `<tr data-mark-roster="${index}"><td>${escapeHtml(`${row.first_name} ${row.last_name || ""}`)}</td><td>${escapeHtml(row.register_no)}</td>
            <td><input class="roster-mark-input" type="number" min="0" max="${Number(row.max_marks)}" step="0.01" value="${row.missing ? "" : escapeHtml(row.marks_obtained)}" data-roster-mark="${index}" aria-label="Marks for ${escapeHtml(row.first_name)}"></td>
            <td><input class="remark-input" type="text" maxlength="1000" value="${escapeHtml(row.remarks || "")}" data-roster-remark="${index}" aria-label="Remark for ${escapeHtml(row.first_name)}"></td></tr>`).join("");
        document.getElementById("marks-entry").hidden = false;
        document.getElementById("marks-save").disabled = false;
    } catch (error) {
        showToast(error.message || "Could not load this marks roster.", "error");
    }
}

async function saveMarksRoster() {
    const assessmentId = Number(document.getElementById("marks-assessment").value);
    const records = state.marksRoster.map((row, index) => {
        const input = document.querySelector(`[data-roster-mark="${index}"]`);
        return { studentId: Number(row.student_id), marksObtained: input.value,
            remarks: document.querySelector(`[data-roster-remark="${index}"]`).value };
    });
    for (const record of records) {
        if (record.marksObtained !== "" && (!Number.isFinite(Number(record.marksObtained)) || Number(record.marksObtained) < 0)) {
            showToast("Marks must be a non-negative number.", "error");
            return;
        }
    }
    const button = document.getElementById("marks-entry-save");
    button.disabled = true;
    try {
        const result = await api("/api/marks/bulk", { method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ assessmentId, records }) });
        document.getElementById("marks-entry").hidden = true;
        document.getElementById("marks-save").disabled = true;
        await loadMarks();
        showToast(result.message);
    } catch (error) {
        showToast(error.message || "Could not save marks.", "error");
    } finally {
        button.disabled = false;
    }
}

async function editMark(id) {
    const row = document.querySelector(`[data-edit-mark="${id}"]`).closest("tr");
    const original = row.cells[3].textContent.trim();
    const marksObtained = window.prompt("Marks obtained", original);
    if (marksObtained === null) return;
    const remarks = window.prompt("Remarks", "");
    if (remarks === null) return;
    try {
        await api(`/api/marks/${id}`, { method: "PUT", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ marksObtained: Number(marksObtained), remarks }) });
        await loadMarks();
        showToast("Student marks updated.");
    } catch (error) {
        showToast(error.message || "Could not update this mark.", "error");
    }
}

async function deleteMark(id) {
    if (!window.confirm("Remove this student's mark? The assessment will show as missing.")) return;
    try {
        await api(`/api/marks/${id}`, { method: "DELETE" });
        await loadMarks();
        showToast("Mark removed.", "info");
    } catch (error) {
        showToast(error.message || "Could not remove this mark.", "error");
    }
}

function updateReportTypes() {
    const category = document.getElementById("report-category").value;
    document.getElementById("report-type").innerHTML = reportOptions[category].map(([value, label]) =>
        `<option value="${value}">${label}</option>`).join("");
    const isStudents = category === "students";
    document.querySelectorAll(".report-date-filter").forEach(element => element.hidden = isStudents);
    document.querySelectorAll(".report-academic-filter").forEach(element => element.hidden = category !== "marks");
    document.getElementById("report-sensitive").disabled = !isStudents;
}

function reportRequest() {
    const form = elements.reportForm;
    const data = new FormData(form);
    const category = data.get("category");
    const request = { type: data.get("type") };
    for (const key of ["startDate", "endDate", "academicYear", "semester", "section", "subjectId", "studentId"]) {
        const value = data.get(key);
        if (value) request[key] = value;
    }
    if (category === "attendance") {
        const subjectId = data.get("subjectId");
        const subject = state.subjects.find(item => String(item.id) === String(subjectId));
        if (subject) request.subject = subject.subject_name;
    }
    return { category, request };
}

async function previewReport() {
    const { category, request } = reportRequest();
    if (document.getElementById("report-sensitive").checked
        && !window.confirm("This preview may display private student identifiers, family contacts, income and address. Continue?")) return;
    request.includeSensitive = document.getElementById("report-sensitive").checked;
    request.sensitiveConfirmed = request.includeSensitive;
    const button = document.getElementById("report-preview");
    button.disabled = true;
    try {
        const report = await api(`/api/reports/${category}/preview`, {
            method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(request)
        });
        state.reportData = { category, request, format: document.getElementById("report-format").value };
        renderReportPreview(report);
        document.getElementById("report-download").disabled = false;
        showToast("Report preview ready.", "info");
    } catch (error) {
        state.reportData = null;
        document.getElementById("report-download").disabled = true;
        showToast(error.message || "Could not preview this report.", "error");
    } finally {
        button.disabled = false;
    }
}

function renderReportPreview(report) {
    document.getElementById("report-preview-title").textContent = report.title;
    document.getElementById("report-record-count").textContent = report.recordCount;
    document.getElementById("report-preview-meta").textContent = `Generated ${report.generatedAt} · ${report.filters}${report.truncated ? " · Showing first 100 rows" : ""}`;
    document.getElementById("report-head").innerHTML = `<tr>${report.columns.map(column => `<th>${escapeHtml(column)}</th>`).join("")}</tr>`;
    if (!report.rows.length) {
        document.getElementById("report-rows").innerHTML = `<tr><td class="table-message" colspan="${Math.max(1, report.columns.length)}">No records match the selected filters.</td></tr>`;
        return;
    }
    document.getElementById("report-rows").innerHTML = report.rows.map(row =>
        `<tr>${report.columns.map(column => `<td>${escapeHtml(row[column] == null ? "" : row[column])}</td>`).join("")}</tr>`
    ).join("");
}

async function downloadReport() {
    if (!state.reportData) {
        showToast("Preview the report before downloading.", "error");
        return;
    }
    const sensitive = Boolean(state.reportData.request.includeSensitive);
    if (sensitive && !window.confirm("You are exporting sensitive student information. Store and share this file securely. Continue?")) return;
    const format = document.getElementById("report-format").value;
    const button = document.getElementById("report-download");
    button.disabled = true;
    try {
        const response = await fetch(`/api/reports/${state.reportData.category}?format=${encodeURIComponent(format)}`, {
            method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(state.reportData.request)
        });
        if (!response.ok) {
            const body = await response.json();
            throw new Error(body.error || `Export failed (${response.status}).`);
        }
        const blob = await response.blob();
        const disposition = response.headers.get("content-disposition") || "";
        const match = disposition.match(/filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i);
        const filename = match ? decodeURIComponent(match[1] || match[2]) : `studsrm-report.${format}`;
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = filename;
        link.click();
        URL.revokeObjectURL(link.href);
        showToast(`${format.toUpperCase()} report downloaded.`);
    } catch (error) {
        showToast(error.message || "Could not download this report.", "error");
    } finally {
        button.disabled = false;
    }
}

async function loadSmartQueryPage() {
    try {
        const [suggestions] = await Promise.all([
            api("/api/smart-query/suggestions"),
            loadCertificateCatalog()
        ]);
        document.getElementById("smart-query-suggestions").innerHTML = suggestions.map(suggestion =>
            `<button class="query-chip" type="button" data-query-suggestion="${escapeHtml(suggestion)}">${escapeHtml(suggestion)}</button>`
        ).join("");
    } catch (error) {
        document.getElementById("smart-query-suggestions").innerHTML =
            `<span class="fields-empty">${escapeHtml(error.message || "Suggestions are unavailable.")}</span>`;
        showToast(error.message || "Could not load Smart Query suggestions.", "error");
    }
}

async function runSmartQuery(query) {
    const input = document.getElementById("smart-query-input");
    const button = document.getElementById("smart-query-submit");
    if (query != null) input.value = query;
    const value = input.value.trim();
    if (!value) {
        showToast("Enter a question to search student records.", "error");
        input.focus();
        return;
    }
    button.disabled = true;
    button.textContent = "Searching…";
    document.getElementById("smart-query-count").textContent = "…";
    document.getElementById("smart-query-message").textContent = "Searching student records…";
    document.getElementById("smart-query-rows").innerHTML =
        `<tr><td class="table-message"><span class="loader"></span> Searching securely…</td></tr>`;
    document.getElementById("smart-query-export").disabled = true;
    try {
        const result = await api("/api/smart-query", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ query: value })
        });
        state.smartQueryResult = result;
        renderSmartQuery(result);
    } catch (error) {
        state.smartQueryResult = null;
        document.getElementById("smart-query-count").textContent = "!";
        document.getElementById("smart-query-message").textContent = error.message || "The query could not be understood.";
        document.getElementById("smart-query-interpretation").hidden = true;
        document.getElementById("smart-query-head").innerHTML = "<tr><th>QUERY HELP</th></tr>";
        document.getElementById("smart-query-rows").innerHTML =
            `<tr><td class="table-message">${escapeHtml(error.message || "Try “Students interested in TCS” or “Students below 75% attendance”.")}</td></tr>`;
        document.getElementById("smart-query-footer").textContent = "Choose one of the suggested queries or try another wording.";
    } finally {
        button.disabled = false;
        button.textContent = "Search";
    }
}

function renderSmartQuery(result) {
    document.getElementById("smart-query-count").textContent = result.count;
    document.getElementById("smart-query-message").textContent = result.summary;
    const filters = Object.entries(result.appliedFilters || {});
    const interpretation = document.getElementById("smart-query-interpretation");
    interpretation.hidden = false;
    interpretation.innerHTML = `<div><span class="interpretation-label">Your query</span><strong>${escapeHtml(result.query)}</strong></div>
        <div><span class="interpretation-label">Interpretation</span><strong>${escapeHtml(result.interpretedAs)}</strong></div>
        <div class="applied-filters"><span class="interpretation-label">Applied filters</span>
            ${filters.length ? filters.map(([label, value]) => `<span class="filter-chip"><b>${escapeHtml(label)}</b> ${escapeHtml(value)}</span>`).join("") : `<span class="filter-chip">No additional filters</span>`}</div>`;
    document.getElementById("smart-query-head").innerHTML =
        `<tr>${result.columns.map(column => `<th>${escapeHtml(column)}</th>`).join("")}</tr>`;
    document.getElementById("smart-query-footer").textContent = `${result.count} matching ${result.count === 1 ? "record" : "records"} · Query uses local student data only.`;
    if (!result.results.length) {
        document.getElementById("smart-query-rows").innerHTML =
            `<tr><td colspan="${Math.max(result.columns.length, 1)}"><div class="empty-state"><strong>No matching records</strong><span>Try a broader company, certificate name, subject, or attendance threshold.</span></div></td></tr>`;
    } else {
        document.getElementById("smart-query-rows").innerHTML = result.results.map(row =>
            `<tr>${result.columns.map(column => `<td>${escapeHtml(row[column] == null || row[column] === "" ? "—" : row[column])}</td>`).join("")}</tr>`
        ).join("");
    }
    document.getElementById("smart-query-export").disabled = !result.results.length;
}

async function exportSmartQuery() {
    const result = state.smartQueryResult;
    if (!result) {
        showToast("Run a query before exporting results.", "error");
        return;
    }
    const button = document.getElementById("smart-query-export");
    const format = document.getElementById("smart-query-format").value;
    button.disabled = true;
    try {
        const response = await fetch(`/api/smart-query/export?format=${encodeURIComponent(format)}`, {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ query: result.query })
        });
        if (!response.ok) {
            const body = await response.json();
            throw new Error(body.error || `Export failed (${response.status}).`);
        }
        const blob = await response.blob();
        const disposition = response.headers.get("content-disposition") || "";
        const match = disposition.match(/filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i);
        const filename = match ? decodeURIComponent(match[1] || match[2]) : `studsrm-smart-query.${format}`;
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = filename;
        link.click();
        URL.revokeObjectURL(link.href);
        showToast(`${format.toUpperCase()} query results exported.`);
    } catch (error) {
        showToast(error.message || "Could not export query results.", "error");
    } finally {
        button.disabled = !state.smartQueryResult;
    }
}

async function loadCertificateCatalog() {
    const certificates = await api("/api/certificates");
    state.certificates = certificates;
    document.getElementById("certificate-catalog-count").textContent = certificates.length;
    document.getElementById("certificate-catalog").innerHTML = certificates.length
        ? certificates.map(certificate => `<div class="custom-field-item"><span class="custom-field-symbol">✓</span>
            <div class="custom-field-copy"><strong>${escapeHtml(certificate.certificateName)}</strong>
                <span>${escapeHtml(certificate.issuingOrganization || "No issuing organization")}</span></div>
            <button class="remove-field certificate-remove" type="button" data-delete-certificate="${Number(certificate.id)}">Remove</button></div>`).join("")
        : `<div class="fields-empty">No certificates yet. Add one to make it available in student profiles.</div>`;
    const select = document.getElementById("student-certificate-select");
    const selected = select.value;
    select.innerHTML = `<option value="">Choose certificate</option>${certificates.map(certificate =>
        `<option value="${Number(certificate.id)}">${escapeHtml(certificate.certificateName)}</option>`).join("")}`;
    if (certificates.some(certificate => String(certificate.id) === selected)) select.value = selected;
    return certificates;
}

async function createCertificate() {
    const name = document.getElementById("certificate-name").value.trim();
    if (!name) {
        document.getElementById("certificate-name").focus();
        showToast("Enter a certificate name.", "error");
        return;
    }
    const button = document.getElementById("certificate-create");
    button.disabled = true;
    try {
        await api("/api/certificates", {
            method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                certificateName: name,
                issuingOrganization: document.getElementById("certificate-organization").value,
                description: document.getElementById("certificate-description").value
            })
        });
        document.getElementById("certificate-name").value = "";
        document.getElementById("certificate-organization").value = "";
        document.getElementById("certificate-description").value = "";
        await loadCertificateCatalog();
        showToast("Certificate added.");
    } catch (error) {
        showToast(error.message || "Could not add certificate.", "error");
    } finally {
        button.disabled = false;
    }
}

async function removeCertificate(certificateId) {
    if (!window.confirm("Remove this certificate and its student status records?")) return;
    try {
        await api(`/api/certificates/${certificateId}`, { method: "DELETE" });
        await loadCertificateCatalog();
        if (state.currentStudentId) await loadStudentCertificates(state.currentStudentId);
        showToast("Certificate removed.");
    } catch (error) {
        showToast(error.message || "Could not remove certificate.", "error");
    }
}

async function loadStudentCertificates(studentId) {
    const target = document.getElementById("student-certificates-rows");
    target.innerHTML = `<tr><td colspan="6" class="table-message"><span class="loader"></span> Loading certificates…</td></tr>`;
    try {
        const [rows] = await Promise.all([
            api(`/api/students/${studentId}/certificates`),
            loadCertificateCatalog()
        ]);
        state.studentCertificates = rows;
        target.innerHTML = rows.length ? rows.map(row => `<tr><td>${escapeHtml(row.certificateName)}</td>
            <td><span class="performance-badge ${row.status === "Completed" ? "performance-green" : row.status === "In Progress" ? "performance-amber" : "performance-gray"}">${escapeHtml(row.status)}</span></td>
            <td>${escapeHtml(row.completionDate || "—")}</td><td>${escapeHtml(row.certificateNumber || "—")}</td>
            <td>${escapeHtml(row.remarks || "—")}</td><td><button class="text-action" type="button" data-edit-student-certificate="${Number(row.certificateId)}">${row.studentCertificateId ? "Edit" : "Set status"}</button>
                ${row.studentCertificateId ? `<button class="text-action danger-action" type="button" data-remove-student-certificate="${Number(row.certificateId)}">Remove</button>` : ""}</td></tr>`).join("")
            : `<tr><td colspan="6" class="table-message">No certificates in the catalog. Add certificates from Smart Query.</td></tr>`;
    } catch (error) {
        target.innerHTML = `<tr><td colspan="6" class="table-message">${escapeHtml(error.message || "Could not load certificates.")}</td></tr>`;
        showToast(error.message || "Could not load student certificates.", "error");
    }
}

async function saveStudentCertificate() {
    const certificateId = document.getElementById("student-certificate-select").value;
    if (!state.currentStudentId || !certificateId) {
        showToast("Choose a certificate first.", "error");
        return;
    }
    const button = document.getElementById("student-certificate-save");
    const payload = {
        status: document.getElementById("student-certificate-status").value,
        completionDate: document.getElementById("student-certificate-date").value,
        certificateNumber: document.getElementById("student-certificate-number").value,
        remarks: document.getElementById("student-certificate-remarks").value
    };
    button.disabled = true;
    try {
        await api(`/api/students/${state.currentStudentId}/certificates/${certificateId}`, {
            method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        await loadStudentCertificates(state.currentStudentId);
        showToast("Student certificate status saved.");
    } catch (error) {
        showToast(error.message || "Could not save certificate status.", "error");
    } finally {
        button.disabled = false;
    }
}

async function loadStudentPerformance(studentId) {
    const target = document.getElementById("profile-performance");
    target.innerHTML = `<div class="loading-panel"><span class="loader"></span> Loading academic performance…</div>`;
    try {
        const [attendance, marks, performance] = await Promise.all([
            api(`/api/attendance/student/${studentId}/summary`),
            api(`/api/marks/student/${studentId}`),
            api(`/api/marks/performance?studentId=${studentId}`)
        ]);
        const student = (performance.students || [])[0];
        const pct = attendance.attendancePercentage;
        const attendancePercent = pct == null ? "No records" : `${pct}%`;
        const assessmentRows = marks.map(mark => `<tr><td>${escapeHtml(mark.subject_name)}</td><td>${escapeHtml(mark.assessment_name)}</td>
            <td>${mark.missing ? "Missing" : `${escapeHtml(mark.marks_obtained)} / ${escapeHtml(mark.max_marks)}`}</td><td>${escapeHtml(mark.grade || "—")}</td><td>${escapeHtml(mark.remarks || "—")}</td></tr>`).join("");
        const subjects = student ? Object.values(student.subjects || {}) : [];
        const trend = marks.filter(mark => !mark.missing).slice().reverse();
        target.innerHTML = `<div class="performance-overview">
            <article class="summary-card"><span class="summary-label">Attendance</span><strong class="performance-large">${escapeHtml(attendancePercent)}</strong><span class="performance-badge ${pct == null ? "performance-gray" : pct < 75 ? "performance-red" : pct < 85 ? "performance-amber" : "performance-green"}">${escapeHtml(attendance.attendanceIndicator)}</span></article>
            <article class="summary-card"><span class="summary-label">Overall average</span><strong class="performance-large">${student ? `${student.percentage}%` : "—"}</strong><span class="summary-foot">${student ? `Rank ${student.rank} · Grade ${student.grade}` : "No marks recorded"}</span></article>
        </div>
        <div class="performance-overview"><article class="summary-card"><span class="summary-label">Internal average</span><strong class="performance-large">${student && student.internalAverage != null ? `${student.internalAverage}%` : "—"}</strong><span class="summary-foot">Semester exam excluded</span></article></div>
        <section class="field-group"><h3>Subject totals and grades</h3><div class="table-wrap"><table><thead><tr><th>SUBJECT</th><th>TOTAL</th><th>MAXIMUM</th><th>GRADE</th><th>AVERAGE</th></tr></thead><tbody>${subjects.length ? subjects.map(subject => `<tr><td>${escapeHtml(subject.subjectCode)} · ${escapeHtml(subject.subjectName)}</td><td>${escapeHtml(subject.totalMarks)}</td><td>${escapeHtml(subject.maxMarks)}</td><td>${escapeHtml(subject.grade)}</td><td>${escapeHtml(subject.percentage)}%</td></tr>`).join("") : `<tr><td colspan="5" class="table-message">No subject marks recorded.</td></tr>`}</tbody></table></div></section>
        <section class="field-group"><h3>Assessment marks and remarks</h3><div class="table-wrap"><table><thead><tr><th>SUBJECT</th><th>ASSESSMENT</th><th>MARKS</th><th>GRADE</th><th>REMARKS</th></tr></thead><tbody>${assessmentRows || `<tr><td colspan="5" class="table-message">No assessments available.</td></tr>`}</tbody></table></div></section>
        <section class="field-group"><h3>Performance trend</h3><div class="trend-bars">${trend.length ? trend.map((mark, index) => `<div class="trend-item"><span>${escapeHtml(mark.assessment_name)}</span><div class="trend-track"><i style="width:${Math.max(0, Math.min(100, Number(mark.percentage)))}%"></i></div><strong>${Number(mark.percentage)}%</strong></div>`).join("") : `<div class="fields-empty">A performance trend will appear once marks are recorded.</div>`}</div></section>`;
    } catch (error) {
        target.innerHTML = `<div class="fields-empty">${escapeHtml(error.message || "Could not load academic performance.")}</div>`;
    }
}

function setView(view) {
    state.view = view;
    const directoryElements = [document.querySelector(".page-heading"), document.querySelector(".workspace > .summary-grid"),
        document.querySelector(".directory-card"), document.querySelector(".page-footer")];
    directoryElements.forEach(element => element.hidden = view !== "directory");
    document.querySelectorAll(".module-page").forEach(element => element.hidden = element.id !== `${view}-view`);
    document.querySelectorAll("[data-view]").forEach(button => {
        const active = button.dataset.view === view;
        button.classList.toggle("active", active);
        if (active) button.setAttribute("aria-current", "page");
        else button.removeAttribute("aria-current");
    });
    const labels = { directory: "Student directory", attendance: "Attendance", marks: "Marks", reports: "Reports", "smart-query": "Smart Query" };
    document.querySelector(".breadcrumb strong").textContent = labels[view];
    if (view === "attendance") loadAttendance();
    if (view === "marks") {
        loadAcademicReferenceData().then(() => Promise.all([loadAssessments(), loadMarks()]));
    }
    if (view === "reports") loadAcademicReferenceData();
    if (view === "smart-query") loadSmartQueryPage();
}

document.getElementById("refresh").addEventListener("click", () => refreshDashboard(true));
document.getElementById("add-student").addEventListener("click", () => openStudentProfile());
document.getElementById("custom-fields-open").addEventListener("click", openCustomFields);
document.getElementById("import-open").addEventListener("click", () => elements.importFile.click());
document.getElementById("delete-student").addEventListener("click", deleteStudent);
elements.studentForm.addEventListener("submit", saveStudent);
elements.fieldForm.addEventListener("submit", addCustomField);
elements.attendanceForm.addEventListener("submit", saveAttendance);
elements.reportForm.addEventListener("submit", event => event.preventDefault());
document.getElementById("attendance-create").addEventListener("click", () => openAttendanceEditor());
document.getElementById("attendance-load-roster").addEventListener("click", () => loadAttendanceRoster());
document.getElementById("attendance-refresh").addEventListener("click", loadAttendance);
document.getElementById("attendance-filter-apply").addEventListener("click", loadAttendance);
document.getElementById("attendance-set-all").addEventListener("change", event => {
    state.attendanceRoster.forEach(record => record.status = event.target.value);
    renderAttendanceRoster();
});
document.getElementById("subject-form").addEventListener("submit", submitSubject);
document.getElementById("assessment-form").addEventListener("submit", submitAssessment);
document.getElementById("marks-filter-apply").addEventListener("click", async () => {
    await loadAssessments();
    await loadMarks();
});
document.getElementById("marks-load-roster").addEventListener("click", openMarksRoster);
document.getElementById("marks-save").addEventListener("click", saveMarksRoster);
document.getElementById("marks-entry-save").addEventListener("click", saveMarksRoster);
document.getElementById("marks-entry-cancel").addEventListener("click", () => {
    document.getElementById("marks-entry").hidden = true;
    document.getElementById("marks-save").disabled = true;
});
document.getElementById("report-category").addEventListener("change", updateReportTypes);
document.getElementById("report-preview").addEventListener("click", previewReport);
document.getElementById("report-download").addEventListener("click", downloadReport);
document.getElementById("report-format").addEventListener("change", () => {
    if (state.reportData) state.reportData.format = document.getElementById("report-format").value;
});
document.getElementById("smart-query-form").addEventListener("submit", event => {
    event.preventDefault();
    runSmartQuery();
});
document.getElementById("smart-query-suggestions").addEventListener("click", event => {
    const chip = event.target.closest("[data-query-suggestion]");
    if (chip) runSmartQuery(chip.dataset.querySuggestion);
});
document.getElementById("smart-query-export").addEventListener("click", exportSmartQuery);
document.getElementById("certificate-create").addEventListener("click", createCertificate);
document.getElementById("certificate-catalog").addEventListener("click", event => {
    const button = event.target.closest("[data-delete-certificate]");
    if (button) removeCertificate(Number(button.dataset.deleteCertificate));
});
document.getElementById("student-certificate-save").addEventListener("click", saveStudentCertificate);
document.getElementById("profile-certificates").addEventListener("keydown", event => {
    if (event.key === "Enter" && event.target.tagName !== "BUTTON") event.preventDefault();
});
document.getElementById("student-certificates-rows").addEventListener("click", async event => {
    const edit = event.target.closest("[data-edit-student-certificate]");
    if (edit) {
        const row = state.studentCertificates.find(item => Number(item.certificateId) === Number(edit.dataset.editStudentCertificate));
        if (!row) return;
        document.getElementById("student-certificate-select").value = row.certificateId;
        document.getElementById("student-certificate-status").value = row.status || "Not Started";
        document.getElementById("student-certificate-date").value = row.completionDate || "";
        document.getElementById("student-certificate-number").value = row.certificateNumber || "";
        document.getElementById("student-certificate-remarks").value = row.remarks || "";
        return;
    }
    const remove = event.target.closest("[data-remove-student-certificate]");
    if (remove) {
        const certificateId = Number(remove.dataset.removeStudentCertificate);
        if (!window.confirm("Remove this student's certificate status?")) return;
        try {
            await api(`/api/students/${state.currentStudentId}/certificates/${certificateId}`, { method: "DELETE" });
            await loadStudentCertificates(state.currentStudentId);
            showToast("Student certificate status removed.");
        } catch (error) {
            showToast(error.message || "Could not remove certificate status.", "error");
        }
    }
});
elements.reportForm.addEventListener("input", event => {
    if (event.target.name === "format") return;
    state.reportData = null;
    document.getElementById("report-download").disabled = true;
});
elements.reportForm.addEventListener("change", event => {
    if (event.target.name === "format") return;
    state.reportData = null;
    document.getElementById("report-download").disabled = true;
});
document.getElementById("marks-subject").addEventListener("change", loadAssessments);
document.getElementById("marks-assessment").addEventListener("change", loadMarks);
document.getElementById("marks-section").addEventListener("change", loadAssessments);
document.getElementById("marks-semester").addEventListener("change", loadAssessments);
document.getElementById("marks-year").addEventListener("change", loadAssessments);
document.querySelectorAll("[data-view]").forEach(button => button.addEventListener("click", () => setView(button.dataset.view)));
document.querySelectorAll("[data-profile-tab]").forEach(button => button.addEventListener("click", async () => {
    state.profileTab = button.dataset.profileTab;
    document.querySelectorAll("[data-profile-tab]").forEach(tab => {
        const selected = tab === button;
        tab.classList.toggle("active", selected);
        tab.setAttribute("aria-selected", String(selected));
    });
    document.getElementById("profile-content").hidden = state.profileTab !== "details";
    document.getElementById("profile-performance").hidden = state.profileTab !== "performance";
    document.getElementById("profile-certificates").hidden = state.profileTab !== "certificates";
    document.getElementById("save-student").hidden = state.profileTab === "certificates";
    document.getElementById("delete-student").hidden = !state.currentStudentId || state.profileTab === "certificates";
    if (state.profileTab === "performance" && state.currentStudentId) await loadStudentPerformance(state.currentStudentId);
    if (state.profileTab === "certificates" && state.currentStudentId) await loadStudentCertificates(state.currentStudentId);
}));

elements.rows.addEventListener("click", event => {
    const button = event.target.closest("[data-student-id]");
    if (button) openStudentProfile(button.dataset.studentId);
});
document.getElementById("attendance-rows").addEventListener("click", event => {
    const edit = event.target.closest("[data-edit-attendance]");
    const remove = event.target.closest("[data-delete-attendance]");
    if (edit) openAttendanceEditor(Number(edit.dataset.editAttendance));
    if (remove) deleteAttendance(Number(remove.dataset.deleteAttendance));
});
document.getElementById("attendance-roster-rows").addEventListener("change", event => {
    const index = event.target.dataset.attendanceStatus;
    if (index != null) state.attendanceRoster[Number(index)].status = event.target.value;
});
document.getElementById("attendance-roster-rows").addEventListener("input", event => {
    const index = event.target.dataset.attendanceRemark;
    if (index != null) state.attendanceRoster[Number(index)].remarks = event.target.value;
});
document.getElementById("subject-list").addEventListener("click", event => {
    const edit = event.target.closest("[data-edit-subject]");
    const remove = event.target.closest("[data-delete-subject]");
    if (edit) editSubject(Number(edit.dataset.editSubject));
    if (remove) deleteSubject(Number(remove.dataset.deleteSubject));
});
document.getElementById("assessment-list").addEventListener("click", event => {
    const edit = event.target.closest("[data-edit-assessment]");
    const remove = event.target.closest("[data-delete-assessment]");
    if (edit) editAssessment(Number(edit.dataset.editAssessment));
    if (remove) deleteAssessment(Number(remove.dataset.deleteAssessment));
});
document.getElementById("marks-rows").addEventListener("click", event => {
    const edit = event.target.closest("[data-edit-mark]");
    const remove = event.target.closest("[data-delete-mark]");
    if (edit) editMark(Number(edit.dataset.editMark));
    if (remove) deleteMark(Number(remove.dataset.deleteMark));
});
elements.customFieldList.addEventListener("click", event => {
    const button = event.target.closest("[data-field-key]");
    if (button) removeCustomField(button.dataset.fieldKey);
});

for (const filter of [elements.section, elements.residency, elements.scholarship]) {
    filter.addEventListener("change", loadStudents);
}
elements.search.addEventListener("input", () => {
    window.clearTimeout(state.searchTimer);
    state.searchTimer = window.setTimeout(loadStudents, 240);
});
elements.importFile.addEventListener("change", event => {
    const file = event.target.files && event.target.files[0];
    if (file) importWorkbook(file);
});

document.querySelectorAll("[data-close]").forEach(button => {
    button.addEventListener("click", () => closeDialog(document.getElementById(button.dataset.close)));
});
for (const dialog of [elements.profileDialog, elements.fieldsDialog, elements.attendanceDialog]) {
    dialog.addEventListener("mousedown", event => {
        if (event.target === dialog) closeDialog(dialog);
    });
}
document.addEventListener("keydown", event => {
    if (event.key === "Escape" && state.activeDialog) closeDialog(state.activeDialog);
    if (event.key === "/" && !state.activeDialog && !["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) {
        event.preventDefault();
        elements.search.focus();
    }
    if (event.key === "Tab" && state.activeDialog) {
        const panel = state.activeDialog.querySelector('[role="dialog"]');
        const focusable = Array.from(panel.querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'))
            .filter(element => !element.hidden);
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }
});

refreshDashboard();
updateReportTypes();
document.getElementById("subject-list").innerHTML = `<div class="fields-empty">Loading subjects…</div>`;
loadAcademicReferenceData().then(renderSubjectList);
