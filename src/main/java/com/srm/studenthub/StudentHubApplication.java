package com.srm.studenthub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StudentHubApplication {
    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get("data"));
        SpringApplication.run(StudentHubApplication.class, args);
    }
}
