package com.taskengine.service;

import com.taskengine.model.EmergencyReport;
import com.taskengine.model.Employee;
import com.taskengine.model.TaskItem;
import com.taskengine.repository.EmergencyReportRepository;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.TaskItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmergencyReportService {

    private final EmergencyReportRepository repository;
    private final NotificationService notificationService;
    private final EmployeeRepository employeeRepository;
    private final TaskItemRepository taskItemRepository;
    private final StreamService streamService;

    public EmergencyReportService(EmergencyReportRepository repository,
                                  NotificationService notificationService,
                                  EmployeeRepository employeeRepository,
                                  TaskItemRepository taskItemRepository,
                                  StreamService streamService) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.employeeRepository = employeeRepository;
        this.taskItemRepository = taskItemRepository;
        this.streamService = streamService;
    }

    @Transactional
    public EmergencyReport submitReport(EmergencyReport report) {
        if (report.getEmployeeId() > 0) {
            employeeRepository.findById(report.getEmployeeId()).ifPresent(emp -> {
                if (report.getEmployeeName() == null || report.getEmployeeName().isBlank()) {
                    report.setEmployeeName(emp.getName());
                }
            });
        }
        if (report.getTaskId() != null && report.getTaskId() > 0) {
            taskItemRepository.findById(report.getTaskId()).ifPresent(t -> {
                if (report.getTaskTitle() == null || report.getTaskTitle().isBlank()) {
                    report.setTaskTitle(t.getTitle());
                }
            });
        }
        report.setStatus("OPEN");
        report.setCreatedAt(LocalDateTime.now());
        EmergencyReport saved = repository.save(report);

        // Notify managers that a new emergency was submitted
        String empName = saved.getEmployeeName() != null ? saved.getEmployeeName() : ("Employee #" + saved.getEmployeeId());
        String msg = String.format("🚨 Emergency (%s) reported by %s: %s", saved.getType(), empName, saved.getDescription());
        notificationService.createNotification(null, msg, "EMERGENCY", saved.getTaskId(), saved.getEmployeeId());

        streamService.broadcastEvent("EMERGENCY_REPORTED", saved);
        return saved;
    }

    public List<EmergencyReport> getAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<EmergencyReport> getByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtDesc(status);
    }

    public List<EmergencyReport> getByEmployeeId(int employeeId) {
        return repository.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    @Transactional
    public EmergencyReport acknowledge(int id) {
        EmergencyReport er = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Emergency report not found: " + id));
        er.setStatus("ACKNOWLEDGED");
        er.setAcknowledgedAt(LocalDateTime.now());
        EmergencyReport saved = repository.save(er);

        notificationService.createNotification(er.getEmployeeId(),
                "Your emergency report regarding '" + (er.getTaskTitle() != null ? er.getTaskTitle() : er.getType()) + "' has been acknowledged by the manager.",
                "INFO", er.getTaskId(), er.getEmployeeId());

        streamService.broadcastEvent("EMERGENCY_UPDATED", saved);
        return saved;
    }

    @Transactional
    public EmergencyReport resolve(int id, String managerResponse) {
        EmergencyReport er = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Emergency report not found: " + id));
        er.setStatus("RESOLVED");
        er.setResolvedAt(LocalDateTime.now());
        if (managerResponse != null && !managerResponse.isBlank()) {
            er.setManagerResponse(managerResponse);
        }
        EmergencyReport saved = repository.save(er);

        notificationService.createNotification(er.getEmployeeId(),
                "Your emergency report has been resolved by the manager. " + (er.getManagerResponse() != null ? "Response: " + er.getManagerResponse() : ""),
                "INFO", er.getTaskId(), er.getEmployeeId());

        streamService.broadcastEvent("EMERGENCY_UPDATED", saved);
        return saved;
    }
}
