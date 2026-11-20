package com.taskengine.controller;

import com.taskengine.model.Employee;
import com.taskengine.model.Role;
import com.taskengine.model.TaskItem;
import com.taskengine.model.UserAccount;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.repository.UserAccountRepository;
import com.taskengine.service.AssignmentService;
import com.taskengine.service.NotificationService;
import com.taskengine.service.StreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
@CrossOrigin(origins = "*")
public class EmployeeController {

    private final EmployeeRepository employeeRepository;
    private final TaskItemRepository taskRepository;
    private final UserAccountRepository userAccountRepository;
    private final AssignmentService assignmentService;
    private final NotificationService notificationService;
    private final StreamService streamService;
    private final PasswordEncoder passwordEncoder;
    private final com.taskengine.repository.AssignmentRepository assignmentRepository;
    private final com.taskengine.service.PreemptiveSchedulingService schedulingService;

    public EmployeeController(EmployeeRepository employeeRepository,
                              TaskItemRepository taskRepository,
                              UserAccountRepository userAccountRepository,
                              AssignmentService assignmentService,
                              NotificationService notificationService,
                              StreamService streamService,
                              PasswordEncoder passwordEncoder,
                              com.taskengine.repository.AssignmentRepository assignmentRepository,
                              com.taskengine.service.PreemptiveSchedulingService schedulingService) {
        this.employeeRepository = employeeRepository;
        this.taskRepository = taskRepository;
        this.userAccountRepository = userAccountRepository;
        this.assignmentService = assignmentService;
        this.notificationService = notificationService;
        this.streamService = streamService;
        this.passwordEncoder = passwordEncoder;
        this.assignmentRepository = assignmentRepository;
        this.schedulingService = schedulingService;
    }

    @GetMapping
    public List<Employee> getEmployees(@RequestParam(required = false) String skill,
                                       @RequestParam(required = false) Boolean available,
                                       @RequestParam(required = false) String search) {
        List<Employee> list = employeeRepository.findAll();
        if (skill != null && !skill.isBlank()) {
            list = list.stream().filter(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(skill.trim())))
                    .collect(Collectors.toList());
        }
        if (available != null) {
            list = list.stream().filter(e -> e.isAvailable() == available)
                    .collect(Collectors.toList());
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase().trim();
            list = list.stream().filter(e -> (e.getName() != null && e.getName().toLowerCase().contains(q))
                    || (e.getRole() != null && e.getRole().toLowerCase().contains(q))
                    || (e.getEmail() != null && e.getEmail().toLowerCase().contains(q))
                    || (e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.toLowerCase().contains(q))))
                    .collect(Collectors.toList());
        }

        List<TaskItem> allTasks = taskRepository.findAll();
        for (Employee emp : list) {
            long activeCount = allTasks.stream()
                    .filter(t -> t.getAssignedEmployeeId() != null && t.getAssignedEmployeeId() == emp.getId()
                            && ("Assigned".equalsIgnoreCase(t.getStatus()) || "In Progress".equalsIgnoreCase(t.getStatus()) || "Paused".equalsIgnoreCase(t.getStatus())))
                    .count();
            emp.setActiveTaskCount((int) activeCount);
        }

        return list;
    }

    @GetMapping("/{id}")
    public Employee getEmployee(@PathVariable int id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
        long activeCount = taskRepository.findByAssignedEmployeeId(id).stream()
                .filter(t -> "Assigned".equalsIgnoreCase(t.getStatus()) || "In Progress".equalsIgnoreCase(t.getStatus()) || "Paused".equalsIgnoreCase(t.getStatus()))
                .count();
        emp.setActiveTaskCount((int) activeCount);
        return emp;
    }

    @GetMapping("/{id}/tasks")
    public List<TaskItem> getEmployeeTasks(@PathVariable int id) {
        return taskRepository.findByAssignedEmployeeId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Employee addEmployee(@RequestBody Employee employee) {
        employee.setId(0);
        if (employee.getEmail() == null || employee.getEmail().isBlank()) {
            employee.setEmail(generateEmail(employee.getName()));
        }
        if (employee.getMaxWorkload() <= 0) {
            employee.setMaxWorkload(40);
        }
        if (employee.getPriority() <= 0) {
            employee.setPriority(1);
        }
        if (employee.getPerformance() <= 0) {
            employee.setPerformance(5.0);
        }

        Employee saved = employeeRepository.save(employee);

        // Automatically create UserAccount for employee if none exists
        String username = saved.getEmail().toLowerCase();
        if (userAccountRepository.findByUsername(username).isEmpty()) {
            UserAccount account = new UserAccount(username, passwordEncoder.encode("employee123"), Role.EMPLOYEE, saved);
            userAccountRepository.save(account);
        }

        notificationService.createNotification(null,
                "New employee added: " + saved.getName() + " (" + saved.getRole() + ")", "INFO", null, saved.getId());
        streamService.broadcastEvent("EMPLOYEE_ADDED", saved);

        return saved;
    }

    @PutMapping("/{id}")
    public Employee updateEmployee(@PathVariable int id, @RequestBody Employee updated) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        employee.setName(updated.getName());
        employee.setRole(updated.getRole());
        if (updated.getSkillProficiencies() != null && !updated.getSkillProficiencies().isEmpty()) {
            employee.setSkillProficiencies(updated.getSkillProficiencies());
        } else if (updated.getSkills() != null) {
            employee.setSkills(updated.getSkills());
        }
        employee.setAvailable(updated.isAvailable());
        if (updated.getMaxWorkload() > 0) {
            employee.setMaxWorkload(updated.getMaxWorkload());
        }
        if (updated.getEmail() != null && !updated.getEmail().isBlank()) {
            String newEmail = updated.getEmail().trim().toLowerCase();
            employee.setEmail(newEmail);
            userAccountRepository.findAll().stream()
                    .filter(u -> u.getEmployee() != null && u.getEmployee().getId() == id)
                    .forEach(u -> {
                        u.setUsername(newEmail);
                        userAccountRepository.save(u);
                    });
        }
        if (updated.getPriority() > 0) {
            employee.setPriority(updated.getPriority());
        }
        if (updated.getPerformance() > 0) {
            employee.setPerformance(updated.getPerformance());
        }

        Employee saved = employeeRepository.save(employee);
        assignmentService.recalculateWorkload(id);
        if (saved.isAvailable()) {
            schedulingService.onEmployeeAvailable(saved);
        }
        streamService.broadcastEvent("EMPLOYEE_UPDATED", saved);
        return saved;
    }

    @PatchMapping("/{id}/availability")
    public Employee updateAvailability(@PathVariable int id, @RequestBody Map<String, Boolean> body) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        if (body.containsKey("available")) {
            boolean avail = body.get("available");
            employee.setAvailable(avail);
            Employee saved = employeeRepository.save(employee);
            if (avail) {
                schedulingService.onEmployeeAvailable(saved);
            }
            notificationService.createNotification(null,
                    "Employee availability updated: " + employee.getName() + " is now " + (avail ? "Available" : "Unavailable"),
                    "ALERT", null, employee.getId());
            streamService.broadcastEvent("EMPLOYEE_UPDATED", saved);
            return saved;
        }
        return employee;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmployee(@PathVariable int id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        // Check if employee has active assigned tasks
        List<TaskItem> activeTasks = taskRepository.findByAssignedEmployeeId(id).stream()
                .filter(t -> "Assigned".equalsIgnoreCase(t.getStatus()) || "In Progress".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.toList());

        if (!activeTasks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete employee with " + activeTasks.size() + " active task(s). Reassign or complete tasks first.");
        }

        // Nullify foreign key references in assignment audit records to allow safe deletion while preserving history
        List<com.taskengine.model.Assignment> assignments = assignmentRepository.findAll();
        for (com.taskengine.model.Assignment a : assignments) {
            if (a.getEmployee() != null && a.getEmployee().getId() == id) {
                a.setEmployee(null);
                assignmentRepository.save(a);
            }
        }

        // Safe to delete user account first
        userAccountRepository.findAll().stream()
                .filter(u -> u.getEmployee() != null && u.getEmployee().getId() == id)
                .forEach(userAccountRepository::delete);

        employeeRepository.delete(employee);
        streamService.broadcastEvent("EMPLOYEE_DELETED", Map.of("id", id, "name", employee.getName()));
    }

    private String generateEmail(String name) {
        if (name == null || name.isBlank()) return "employee" + System.currentTimeMillis() + "@company.com";
        String clean = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        return clean + "@company.com";
    }
}
