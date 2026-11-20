package com.taskengine;

import com.taskengine.model.Employee;
import com.taskengine.model.Role;
import com.taskengine.model.TaskItem;
import com.taskengine.model.UserAccount;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.repository.UserAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private final EmployeeRepository employeeRepository;
    private final TaskItemRepository taskRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(EmployeeRepository employeeRepository,
                               TaskItemRepository taskRepository,
                               UserAccountRepository userAccountRepository,
                               PasswordEncoder passwordEncoder,
                               JdbcTemplate jdbcTemplate) {
        this.employeeRepository = employeeRepository;
        this.taskRepository = taskRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute("UPDATE employees SET version = 0 WHERE version IS NULL");
            jdbcTemplate.execute("UPDATE employees SET max_workload = 40 WHERE max_workload IS NULL");
            jdbcTemplate.execute("UPDATE employees SET workload_hours = 0 WHERE workload_hours IS NULL");
            jdbcTemplate.execute("UPDATE employees SET priority = 1 WHERE priority IS NULL");
            jdbcTemplate.execute("UPDATE employees SET performance = 5.0 WHERE performance IS NULL");
        } catch (Exception ignored) {}
        // 1. Ensure Manager account exists
        if (userAccountRepository.findByUsername("manager").isEmpty()) {
            UserAccount manager = new UserAccount(
                    "manager",
                    passwordEncoder.encode("manager123"),
                    Role.MANAGER,
                    null
            );
            userAccountRepository.save(manager);
            System.out.println(">>> Initialized default manager account: manager / manager123");
        }

        // 2. Only if no employees exist in DB, initialize the curated 10-employee workforce
        if (employeeRepository.count() == 0) {
            seedEmployee("Alsaba Awati", "alsaba@company.com", "Software Developer", List.of("Java", "Python", "Security"), true, 40);
            seedEmployee("Omkar Mahendrakar", "emp2@company.com", "Software Developer", List.of("Java", "SQL", "Testing"), true, 40);
            seedEmployee("Harsh", "harsh@gmail.com", "DevOps Engineer", List.of("DevOps", "Security", "Docker"), true, 7);
            seedEmployee("Rakshita", "alsabaawati2006@gmail.com", "DevOps Engineer", List.of("DevOps", "Python", "Database Management"), true, 12);
            seedEmployee("Alex Rivera", "emp1@company.com", "Full Stack Engineer", List.of("Java", "React", "SQL", "JavaScript"), true, 40);
            seedEmployee("Jiya", "jiyaawati2006@gmail.com", "Senior Backend Developer", List.of("Spring Boot", "Java", "Database Management"), true, 13);
            seedEmployee("Arman", "arman@gmail.com", "Senior Backend Developer", List.of("Spring Boot", "Python", "SQL"), true, 16);
            seedEmployee("Sakshi", "sakshi@gmail.com", "Frontend Developer", List.of("React js", "HTML/CSS", "JavaScript"), true, 30);
            seedEmployee("Keerthana", "keerthana@gmail.com", "Frontend Developer", List.of("React js", "HTML/CSS", "Testing"), true, 5);
            seedEmployee("Pooja", "pooja@gmail.com", "Cloud Designer", List.of("CSS", "HTML/CSS", "Testing", "Security"), true, 30);

            System.out.println(">>> Initialized 10 clean employees.");
        }

        // 3. Ensure each employee has an email, max workload, and associated UserAccount
        List<Employee> allEmployees = employeeRepository.findAll();
        for (Employee emp : allEmployees) {
            boolean changed = false;
            if (emp.getEmail() == null || emp.getEmail().isBlank()) {
                emp.setEmail("emp_" + emp.getId() + "@taskengine.local");
                changed = true;
            }
            if (emp.getMaxWorkload() <= 0) {
                emp.setMaxWorkload(40);
                changed = true;
            }
            if (changed) {
                emp = employeeRepository.save(emp);
            }

            String username = emp.getEmail().toLowerCase();
            if (userAccountRepository.findByUsername(username).isEmpty()) {
                UserAccount account = new UserAccount(
                        username,
                        passwordEncoder.encode("employee123"),
                        Role.EMPLOYEE,
                        emp
                );
                userAccountRepository.save(account);
            }

            // Ensure skill proficiencies exist for each employee
            if (emp.getSkillProficiencies().isEmpty() && emp.getSkills() != null && !emp.getSkills().isEmpty()) {
                assignDefaultProficiencies(emp);
                employeeRepository.save(emp);
            }
        }

        // 4. If no tasks exist, insert sample task backlog
        if (taskRepository.count() == 0) {
            String nextWeek = LocalDate.now().plusDays(7).toString();
            String twoWeeks = LocalDate.now().plusDays(14).toString();

            taskRepository.save(new TaskItem(0, "Build OAuth2 Security & User Endpoints", "Java", "High", 4, nextWeek, "Pending"));
            taskRepository.save(new TaskItem(0, "Create Interactive Task Analytics Dashboard", "React", "Medium", 3, nextWeek, "Pending"));
            taskRepository.save(new TaskItem(0, "Tune Composite SQL Query Indices", "SQL", "Low", 2, twoWeeks, "Pending"));
            taskRepository.save(new TaskItem(0, "Containerize Microservices with Docker Compose", "Docker", "Critical", 5, nextWeek, "Pending"));
            taskRepository.save(new TaskItem(0, "Implement Predictive Workload Forecaster", "Python", "High", 6, twoWeeks, "Pending"));
            taskRepository.save(new TaskItem(0, "Design Real-time SSE Notification Stream", "Java", "High", 3, nextWeek, "Pending"));

            System.out.println(">>> Initialized 6 initial pending tasks.");
        }
    }

    private void seedEmployee(String name, String email, String role, List<String> skills, boolean available, int maxWorkload) {
        Employee emp = new Employee();
        emp.setName(name);
        emp.setEmail(email);
        emp.setRole(role);
        emp.setSkills(skills);
        emp.setAvailable(available);
        emp.setWorkloadHours(0);
        emp.setMaxWorkload(maxWorkload);
        assignDefaultProficiencies(emp);
        emp = employeeRepository.save(emp);

        String username = email.trim().toLowerCase();
        if (userAccountRepository.findByUsername(username).isEmpty()) {
            userAccountRepository.save(new UserAccount(
                    username,
                    passwordEncoder.encode("employee123"),
                    Role.EMPLOYEE,
                    emp
            ));
        }
    }

    private void assignDefaultProficiencies(Employee emp) {
        if (emp.getSkills() == null) return;
        String name = emp.getName() != null ? emp.getName().toLowerCase() : "";
        for (String skill : emp.getSkills()) {
            String level = "Intermediate";
            String s = skill.toLowerCase();
            if (name.contains("alsaba") && s.contains("java")) level = "Advanced";
            else if (name.contains("alex") && (s.contains("java") || s.contains("react"))) level = "Advanced";
            else if (name.contains("harsh") && s.contains("devops")) level = "Advanced";
            else if (name.contains("rakshita") && s.contains("python")) level = "Advanced";
            else if (name.contains("jiya") && s.contains("spring")) level = "Advanced";
            else if (name.contains("arman") && s.contains("sql")) level = "Advanced";
            else if (name.contains("sakshi") && s.contains("react")) level = "Advanced";
            else if (name.contains("keerthana") && s.contains("css")) level = "Advanced";
            else if (name.contains("pooja") && s.contains("security")) level = "Beginner";
            else if (name.contains("pooja") && s.contains("css")) level = "Advanced";
            else if (emp.getRole() != null && emp.getRole().toLowerCase().contains("senior")) level = "Advanced";
            emp.setProficiency(skill, level);
        }
    }
}
