package com.srm.studenthub;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CertificateCatalogRunner implements ApplicationRunner {
    private static final List<String> CERTIFICATES = Arrays.asList(
        "App Development with Swift Certification",
        "Associate Android Developer",
        "IBM Certified Mobile Application Developer",
        "Certified Full Stack Developer (CFSD)",
        "Oracle Certified Professional: Java SE 11 Developer",
        "Oracle Certified Professional: Java SE 17 Developer",
        "Oracle Cloud Visual Builder Studio 2023 Application Associate",
        "SAP Certified Associate - Back-End Developer - ABAP Cloud",
        "Certified React.js Developer",
        "JSE – Certified Entry-Level JavaScript Programmer",
        "Certified Modern Web development",
        "Salesforce Certified Administrator exam",
        "Salesforce Certified Platform Developer I",
        "Platform App Builder certification",
        "Service Now - Certified System Administrator",
        "Service Now - Certified Application Developer",
        "Certified Pega System Architect",
        "Mulesoft Certified Developer - Level 1",
        "Microsoft Certified : Dynamics 365 Fundamentals (CRM)",
        "SAP Certified Associate - Implementation Consultant - End-to-End Business Processes for the Intelligent Enterprise",
        "Salesforce Certified Agentforce Specialist",
        "UiPath Automation Developer Associate",
        "Blue Prism Certified Associate Developer",
        "Blue Prism Certified Developer",
        "Oracle AI Database SQL Associate (1Z0-171)",
        "Oracle MySQL 8.0 Database Developer - 1Z0-909",
        "MongoDB Associate Developer",
        "Certified Prompt Engineer",
        "Microsoft Certified: Azure AI Fundamentals",
        "Google Cloud Certified: Machine Learning Engineer",
        "Google Cloud Certified: Generative AI Leader",
        "Oracle Cloud Infrastructure 2025 Generative AI Professional",
        "Oracle Cloud Infrastructure Data Science Professional",
        "Oracle AI Autonomous Database 2025 Professional",
        "Oracle Cloud Infrastructure Enterprise AI Professional",
        "AWS Certified AI Practioner",
        "AWS Certified Machine Learning - Specialty",
        "NVIDIA-Certified Associate - Generative AI LLMs",
        "NVIDIA-Certified Associate - Generative AI - Multimodal",
        "Red Hat Certified Specialist in Container",
        "Github Copilot",
        "Azure Data Scientist Associate",
        "Microsoft Certified: Azure AI Apps and Agents Developer Associate",
        "Microsoft Certified: Machine Learning Operations Engineer Associate",
        "AWS Certified Machine Learning Engineer - Associate",
        "Foundation Level Exam (ISTQB)",
        "A4Q Certified Selenium 4 Tester Foundation",
        "A4Q FOUNDATION LEVEL TESTER FOR APPIUM",
        "Certified Associate in Project Management",
        "Professional Scrum Master",
        "Atlassian Certified Associate - ITSM with Jira Service Management Foundations Certification",
        "Atlassian Certified Associate - Jira Software Board Configuration",
        "Atlassian Certified Associate - Jira Software Essentials",
        "Docker Certified Associate",
        "CompTIA IT Fundamentals",
        "Github Actions",
        "AWS Certified Cloud Practioner",
        "AWS Certified Developer - Associate",
        "AWS Certified Solution Architect - Associate",
        "Google Cloud Certified: Cloud Engineer",
        "Google Cloud Certified: Cloud Developer",
        "Google Cloud Certified: Cloud Digital Leader",
        "Microsoft Certified: Azure Fundamentals",
        "Microsoft Certified: Azure Developer Associate",
        "Microsoft Certified: Azure Administrator Associate",
        "Microsoft Certified: Azure Network Engineer Associate",
        "Red Hat Certified Engineer",
        "Oracle APEX Cloud Developer Professional (1Z0-770)",
        "Certified Kubernetes Application Developer(Ckad)",
        "CompTIA Cloud+",
        "AWS Certified CloudOps Engineer - Associate",
        "Fortinet Certified Professional Cloud Security",
        "Fortinet Certified Solution Specialist Cloud Security",
        "Certificate of Cloud Security Knowledge",
        "Linux Essentials",
        "CCNA",
        "EC-Council - Certified Network Defender v2 examination",
        "Juniper Networks Certified Associate(JNCIA) - JUNOS(R & S)",
        "Red Hat Certified System Administrator (RHCSA)",
        "Nutanix Certified Associate 6",
        "Comptia Network Plus",
        "5G Associate-Level Certification",
        "Certified Ethical Hacker (CEH V12)",
        "Cisco certified CyberOps Associate",
        "Microsoft Certified: Security, Compliance, and Identity Fundamentals",
        "Paloalto Networks Certified Cybersecurity Entry-Level Technician",
        "Certified Security Operations Center Analyst",
        "Certified Cloud Security Engineer",
        "Check Point Security Administrator",
        "Cisco Certified Support Technician Cyber Security",
        "Comptia Security Plus(Sy- 701)",
        "Fortinet Certified Solution Specialist Security Operations",
        "Fortinet Certified Solution Specialist Secure Networking",
        "Fortinet Certified Professional Secure Networking",
        "Fortinet Certified Professional Security Operations",
        "CCC Internet Of Things Foundation Certification",
        "Certified Internet-of-Things (IoT) Expert™",
        "Arcitura Certified IoT Architect",
        "CertNexus Certified Internet Of Things Practitioner",
        "Red Hat Certified Enterprise Application Developer"
    );

    private final JdbcTemplate jdbc;

    public CertificateCatalogRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String certificate : CERTIFICATES) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificates WHERE LOWER(certificate_name) = ?",
                Integer.class, certificate.toLowerCase(Locale.ROOT));
            if (count == null || count == 0) {
                jdbc.update("INSERT INTO certificates (certificate_name) VALUES (?)", certificate);
            }
        }
    }
}
