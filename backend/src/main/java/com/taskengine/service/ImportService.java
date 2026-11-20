package com.taskengine.service;

import com.opencsv.CSVReader;
import com.taskengine.dto.ImportResponseDto;
import com.taskengine.model.Employee;
import com.taskengine.model.Role;
import com.taskengine.model.TaskItem;
import com.taskengine.model.UserAccount;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.repository.UserAccountRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ImportService {

    private final EmployeeRepository employeeRepository;
    private final TaskItemRepository taskRepository;
    private final UserAccountRepository userAccountRepository;
    private final NotificationService notificationService;
    private final StreamService streamService;
    private final PasswordEncoder passwordEncoder;

    public ImportService(EmployeeRepository employeeRepository,
                         TaskItemRepository taskRepository,
                         UserAccountRepository userAccountRepository,
                         NotificationService notificationService,
                         StreamService streamService,
                         PasswordEncoder passwordEncoder) {
        this.employeeRepository = employeeRepository;
        this.taskRepository = taskRepository;
        this.userAccountRepository = userAccountRepository;
        this.notificationService = notificationService;
        this.streamService = streamService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Import employees from CSV or Excel file.
     */
    @Transactional
    public ImportResponseDto importEmployees(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".csv")) {
                return importEmployeesFromCsv(file);
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return importEmployeesFromExcel(file);
            } else {
                return new ImportResponseDto(0, 0, 0, 1, 1, List.of("Unsupported file format. Please upload .csv or .xlsx"));
            }
        } catch (Exception e) {
            return new ImportResponseDto(0, 0, 0, 1, 1, List.of("Failed to parse file: " + e.getMessage()));
        }
    }

    private ImportResponseDto importEmployeesFromCsv(MultipartFile file) throws Exception {
        List<String> errors = new ArrayList<>();
        int total = 0, success = 0, duplicates = 0, invalid = 0;

        Set<String> existingEmails = employeeRepository.findAll().stream()
                .map(e -> e.getEmail() != null ? e.getEmail().toLowerCase() : "")
                .collect(Collectors.toSet());
        Set<String> seenInBatch = new HashSet<>();
        List<Employee> toSave = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) {
                return new ImportResponseDto(0, 0, 0, 0, 0, List.of("CSV file is empty"));
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length == 0 || (row.length == 1 && row[0].trim().isEmpty())) continue;
                total++;
                int rowNum = total + 1; // 1-based data row after header

                if (row.length < 3) {
                    errors.add("Row " + rowNum + ": Insufficient columns (expected Name, Role, Skills, etc.)");
                    invalid++;
                    continue;
                }

                String name = row[0].trim();
                String email = (row.length > 1 && !row[1].isBlank()) ? row[1].trim() : generateEmail(name);
                String role = (row.length > 2) ? row[2].trim() : "";
                String skillsStr = (row.length > 3) ? row[3].trim() : "";
                String availStr = (row.length > 4) ? row[4].trim() : "true";
                String maxWorkloadStr = (row.length > 5) ? row[5].trim() : "40";

                // Validations
                if (name.isEmpty() || role.isEmpty()) {
                    errors.add("Row " + rowNum + ": Name and Role are required.");
                    invalid++;
                    continue;
                }

                List<String> skills = Arrays.stream(skillsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                if (skills.isEmpty()) {
                    errors.add("Row " + rowNum + ": At least one skill is required for " + name);
                    invalid++;
                    continue;
                }

                String lowerEmail = email.toLowerCase();
                if (existingEmails.contains(lowerEmail) || seenInBatch.contains(lowerEmail)) {
                    errors.add("Row " + rowNum + ": Duplicate employee email '" + email + "' for " + name);
                    duplicates++;
                    continue;
                }

                boolean available = !"false".equalsIgnoreCase(availStr) && !"unavailable".equalsIgnoreCase(availStr);
                int maxWorkload = 40;
                try {
                    maxWorkload = Integer.parseInt(maxWorkloadStr);
                    if (maxWorkload <= 0) maxWorkload = 40;
                } catch (NumberFormatException ignored) {}

                Employee emp = new Employee();
                emp.setName(name);
                emp.setEmail(email);
                emp.setRole(role);
                emp.setSkills(skills);
                emp.setAvailable(available);
                emp.setMaxWorkload(maxWorkload);
                emp.setWorkloadHours(0);

                toSave.add(emp);
                seenInBatch.add(lowerEmail);
                success++;
            }
        }

        saveEmployeesAndAccounts(toSave);
        notifyImport("employees", success, duplicates, invalid, total);
        return new ImportResponseDto(total, success, duplicates, invalid, total - success, errors);
    }

    private ImportResponseDto importEmployeesFromExcel(MultipartFile file) throws Exception {
        List<String> errors = new ArrayList<>();
        int total = 0, success = 0, duplicates = 0, invalid = 0;

        Set<String> existingEmails = employeeRepository.findAll().stream()
                .map(e -> e.getEmail() != null ? e.getEmail().toLowerCase() : "")
                .collect(Collectors.toSet());
        Set<String> seenInBatch = new HashSet<>();
        List<Employee> toSave = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean isHeader = true;

            for (Row row : sheet) {
                if (isHeader) { isHeader = false; continue; }
                total++;
                int rowNum = row.getRowNum() + 1;

                String name = getCellString(row, 0);
                String email = getCellString(row, 1);
                String role = getCellString(row, 2);
                String skillsStr = getCellString(row, 3);
                String availStr = getCellString(row, 4);
                String maxWorkloadStr = getCellString(row, 5);

                if (name.isEmpty() && role.isEmpty()) continue; // skip blank row

                if (email.isEmpty()) email = generateEmail(name);

                if (name.isEmpty() || role.isEmpty()) {
                    errors.add("Row " + rowNum + ": Name and Role are required.");
                    invalid++;
                    continue;
                }

                List<String> skills = Arrays.stream(skillsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                if (skills.isEmpty()) {
                    errors.add("Row " + rowNum + ": At least one skill is required for " + name);
                    invalid++;
                    continue;
                }

                String lowerEmail = email.toLowerCase();
                if (existingEmails.contains(lowerEmail) || seenInBatch.contains(lowerEmail)) {
                    errors.add("Row " + rowNum + ": Duplicate employee email '" + email + "' for " + name);
                    duplicates++;
                    continue;
                }

                boolean available = !"false".equalsIgnoreCase(availStr) && !"unavailable".equalsIgnoreCase(availStr);
                int maxWorkload = 40;
                try {
                    if (!maxWorkloadStr.isEmpty()) maxWorkload = (int) Double.parseDouble(maxWorkloadStr);
                    if (maxWorkload <= 0) maxWorkload = 40;
                } catch (Exception ignored) {}

                Employee emp = new Employee();
                emp.setName(name);
                emp.setEmail(email);
                emp.setRole(role);
                emp.setSkills(skills);
                emp.setAvailable(available);
                emp.setMaxWorkload(maxWorkload);
                emp.setWorkloadHours(0);

                toSave.add(emp);
                seenInBatch.add(lowerEmail);
                success++;
            }
        }

        saveEmployeesAndAccounts(toSave);
        notifyImport("employees", success, duplicates, invalid, total);
        return new ImportResponseDto(total, success, duplicates, invalid, total - success, errors);
    }

    private void saveEmployeesAndAccounts(List<Employee> employees) {
        List<Employee> saved = employeeRepository.saveAll(employees);
        for (Employee emp : saved) {
            String username = emp.getEmail().toLowerCase();
            if (userAccountRepository.findByUsername(username).isEmpty()) {
                UserAccount account = new UserAccount(username, passwordEncoder.encode("employee123"), Role.EMPLOYEE, emp);
                userAccountRepository.save(account);
            }
        }
    }

    /**
     * Import tasks from CSV or Excel file.
     */
    @Transactional
    public ImportResponseDto importTasks(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".csv")) {
                return importTasksFromCsv(file);
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                return importTasksFromExcel(file);
            } else {
                return new ImportResponseDto(0, 0, 0, 1, 1, List.of("Unsupported file format. Please upload .csv or .xlsx"));
            }
        } catch (Exception e) {
            return new ImportResponseDto(0, 0, 0, 1, 1, List.of("Failed to parse task file: " + e.getMessage()));
        }
    }

    private ImportResponseDto importTasksFromCsv(MultipartFile file) throws Exception {
        List<String> errors = new ArrayList<>();
        int total = 0, success = 0, duplicates = 0, invalid = 0;
        List<TaskItem> toSave = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) {
                return new ImportResponseDto(0, 0, 0, 0, 0, List.of("CSV file is empty"));
            }

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length == 0 || (row.length == 1 && row[0].trim().isEmpty())) continue;
                total++;
                int rowNum = total + 1;

                if (row.length < 4) {
                    errors.add("Row " + rowNum + ": Insufficient columns (expected Title, Description, RequiredSkill, Duration, Priority, Deadline)");
                    invalid++;
                    continue;
                }

                String title = row[0].trim();
                String description = (row.length > 1) ? row[1].trim() : "";
                String requiredSkill = (row.length > 2) ? row[2].trim() : "";
                String durationStr = (row.length > 3) ? row[3].trim() : "2";
                String priority = (row.length > 4) ? row[4].trim() : "Medium";
                String deadline = (row.length > 5) ? row[5].trim() : "";

                if (title.isEmpty() || requiredSkill.isEmpty()) {
                    errors.add("Row " + rowNum + ": Title and Required Skill are mandatory.");
                    invalid++;
                    continue;
                }

                int duration;
                try {
                    duration = Integer.parseInt(durationStr);
                    if (duration <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    errors.add("Row " + rowNum + ": Invalid duration '" + durationStr + "'. Must be a positive integer.");
                    invalid++;
                    continue;
                }

                if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority.toUpperCase())) {
                    priority = "Medium";
                }

                TaskItem task = new TaskItem();
                task.setTitle(title);
                task.setDescription(description);
                task.setRequiredSkill(requiredSkill);
                task.setDurationHours(duration);
                task.setPriority(normalizePriority(priority));
                task.setDeadline(deadline.isBlank() ? LocalDate.now().plusDays(7).toString() : deadline);
                task.setStatus("Pending");
                task.setCreatedAt(LocalDateTime.now());

                toSave.add(task);
                success++;
            }
        }

        taskRepository.saveAll(toSave);
        notifyImport("tasks", success, duplicates, invalid, total);
        return new ImportResponseDto(total, success, duplicates, invalid, total - success, errors);
    }

    private ImportResponseDto importTasksFromExcel(MultipartFile file) throws Exception {
        List<String> errors = new ArrayList<>();
        int total = 0, success = 0, duplicates = 0, invalid = 0;
        List<TaskItem> toSave = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean isHeader = true;

            for (Row row : sheet) {
                if (isHeader) { isHeader = false; continue; }
                total++;
                int rowNum = row.getRowNum() + 1;

                String title = getCellString(row, 0);
                String description = getCellString(row, 1);
                String requiredSkill = getCellString(row, 2);
                String durationStr = getCellString(row, 3);
                String priority = getCellString(row, 4);
                String deadline = getCellString(row, 5);

                if (title.isEmpty() && requiredSkill.isEmpty()) continue;

                if (title.isEmpty() || requiredSkill.isEmpty()) {
                    errors.add("Row " + rowNum + ": Title and Required Skill are mandatory.");
                    invalid++;
                    continue;
                }

                int duration = 2;
                try {
                    duration = (int) Double.parseDouble(durationStr);
                    if (duration <= 0) duration = 2;
                } catch (Exception e) {
                    errors.add("Row " + rowNum + ": Invalid duration '" + durationStr + "'");
                    invalid++;
                    continue;
                }

                if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(priority.toUpperCase())) {
                    priority = "Medium";
                }

                TaskItem task = new TaskItem();
                task.setTitle(title);
                task.setDescription(description);
                task.setRequiredSkill(requiredSkill);
                task.setDurationHours(duration);
                task.setPriority(normalizePriority(priority));
                task.setDeadline(deadline.isBlank() ? LocalDate.now().plusDays(7).toString() : deadline);
                task.setStatus("Pending");
                task.setCreatedAt(LocalDateTime.now());

                toSave.add(task);
                success++;
            }
        }

        taskRepository.saveAll(toSave);
        notifyImport("tasks", success, duplicates, invalid, total);
        return new ImportResponseDto(total, success, duplicates, invalid, total - success, errors);
    }

    private void notifyImport(String entityType, int success, int duplicates, int invalid, int total) {
        String msg = String.format("Bulk import %s complete: %d imported successfully, %d duplicates, %d invalid (Total: %d).",
                entityType, success, duplicates, invalid, total);
        notificationService.createNotification(null, msg, "IMPORT", null, null);
        streamService.broadcastEvent("IMPORT_COMPLETE", Map.of(
                "type", entityType, "success", success, "duplicates", duplicates, "invalid", invalid, "total", total
        ));
    }

    private String getCellString(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default: return "";
        }
    }

    private String normalizePriority(String p) {
        if (p == null) return "Medium";
        switch (p.trim().toUpperCase()) {
            case "CRITICAL": return "Critical";
            case "HIGH": return "High";
            case "LOW": return "Low";
            default: return "Medium";
        }
    }

    private String generateEmail(String name) {
        if (name == null || name.isBlank()) return "emp" + UUID.randomUUID().toString().substring(0, 6) + "@company.com";
        return name.toLowerCase().replaceAll("[^a-z0-9]", "") + "@company.com";
    }
}
