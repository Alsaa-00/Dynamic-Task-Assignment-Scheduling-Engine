package com.taskengine.dto;

import java.time.LocalDateTime;

public class NotificationResponseDto {
    private Long id;
    private Integer employeeId;
    private String message;
    private boolean read;
    private LocalDateTime createdAt;

    public NotificationResponseDto() {}

    public NotificationResponseDto(Long id, Integer employeeId, String message, boolean read, LocalDateTime createdAt) {
        this.id = id;
        this.employeeId = employeeId;
        this.message = message;
        this.read = read;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getEmployeeId() { return employeeId; }
    public void setEmployeeId(Integer employeeId) { this.employeeId = employeeId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
