package com.taskengine.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = true)
    private Integer employeeId; // null for manager or global

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "type")
    private String type = "INFO"; // INFO, EMERGENCY, TASK_ASSIGNED, TASK_COMPLETED, REASSIGNMENT, ALERT

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "related_task_id")
    private Integer relatedTaskId;

    @Column(name = "related_employee_id")
    private Integer relatedEmployeeId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Notification() {}

    public Notification(Integer employeeId, String message) {
        this.employeeId = employeeId;
        this.message = message;
        this.type = "INFO";
        this.read = false;
        this.createdAt = LocalDateTime.now();
    }

    public Notification(Integer employeeId, String message, String type) {
        this.employeeId = employeeId;
        this.message = message;
        this.type = type;
        this.read = false;
        this.createdAt = LocalDateTime.now();
    }

    public Notification(Integer employeeId, String message, String type, Integer relatedTaskId, Integer relatedEmployeeId) {
        this.employeeId = employeeId;
        this.message = message;
        this.type = type;
        this.relatedTaskId = relatedTaskId;
        this.relatedEmployeeId = relatedEmployeeId;
        this.read = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getEmployeeId() { return employeeId; }
    public void setEmployeeId(Integer employeeId) { this.employeeId = employeeId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public Integer getRelatedTaskId() { return relatedTaskId; }
    public void setRelatedTaskId(Integer relatedTaskId) { this.relatedTaskId = relatedTaskId; }

    public Integer getRelatedEmployeeId() { return relatedEmployeeId; }
    public void setRelatedEmployeeId(Integer relatedEmployeeId) { this.relatedEmployeeId = relatedEmployeeId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
