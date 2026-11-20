package com.taskengine.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "emergency_reports")
public class EmergencyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "employee_id", nullable = false)
    private int employeeId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "task_id")
    private Integer taskId;

    @Column(name = "task_title")
    private String taskTitle;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "expected_unavailability")
    private String expectedUnavailability;

    // Status: "OPEN", "ACKNOWLEDGED", "RESOLVED"
    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "manager_response", length = 2000)
    private String managerResponse;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public EmergencyReport() {}

    public EmergencyReport(int employeeId, String type, String description) {
        this.employeeId = employeeId;
        this.type = type;
        this.description = description;
        this.status = "OPEN";
        this.createdAt = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getEmployeeId() { return employeeId; }
    public void setEmployeeId(int employeeId) { this.employeeId = employeeId; }

    public String getEmployeeName() { return employeeName; }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

    public Integer getTaskId() { return taskId; }
    public void setTaskId(Integer taskId) { this.taskId = taskId; }

    public String getTaskTitle() { return taskTitle; }
    public void setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpectedUnavailability() { return expectedUnavailability; }
    public void setExpectedUnavailability(String expectedUnavailability) { this.expectedUnavailability = expectedUnavailability; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getManagerResponse() { return managerResponse; }
    public void setManagerResponse(String managerResponse) { this.managerResponse = managerResponse; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
