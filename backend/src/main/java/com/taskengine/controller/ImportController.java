package com.taskengine.controller;

import com.taskengine.dto.ImportResponseDto;
import com.taskengine.service.ImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/import")
@CrossOrigin(origins = "*")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/employees", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponseDto importEmployees(@RequestParam("file") MultipartFile file) {
        return importService.importEmployees(file);
    }

    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponseDto importTasks(@RequestParam("file") MultipartFile file) {
        return importService.importTasks(file);
    }

    @GetMapping("/template/employees")
    public ResponseEntity<byte[]> getEmployeeTemplate() {
        String csv = "Name,Email,Role,Skills,Availability,MaxWorkload\n" +
                "Asha Rao,asha.rao@company.com,Backend Developer,\"Java, Spring Boot, SQL\",true,40\n" +
                "Riya Sen,riya.sen@company.com,Frontend Developer,\"React, JavaScript, CSS\",true,40\n" +
                "Kiran Kumar,kiran.kumar@company.com,Full Stack Developer,\"Java, React, SQL\",true,40\n" +
                "Dev Patel,dev.patel@company.com,DevOps Engineer,\"Docker, Kubernetes, AWS\",true,40\n";

        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employees_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    @GetMapping("/template/tasks")
    public ResponseEntity<byte[]> getTaskTemplate() {
        String csv = "Title,Description,RequiredSkill,DurationHours,Priority,Deadline\n" +
                "Implement JWT Authentication,Implement user login and token generation,Java,4,High,2026-11-15\n" +
                "Design Navigation Sidebar,Create responsive collapsible navigation,CSS,3,Medium,2026-11-12\n" +
                "Build REST Endpoints,Develop CRUD APIs with Spring Boot,Java,5,High,2026-11-14\n" +
                "Database Index Optimization,Add composite indices to query tables,SQL,2,Low,2026-11-20\n";

        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tasks_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }
}
