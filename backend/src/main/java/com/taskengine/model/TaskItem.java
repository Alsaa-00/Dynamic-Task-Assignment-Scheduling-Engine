package com.taskengine.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "tasks")
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    @Column(name = "required_skill", nullable = false)
    private String requiredSkill;

    private String priority = "Medium";

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours = 2;

    @Column(name = "remaining_duration_hours")
    private Integer remainingDurationHours;

    @Column(name = "progress_percentage")
    private Integer progressPercentage = 0;

    private String deadline;

    @Column(nullable = false)
    private String status = "Pending";

    @Column(name = "assigned_employee_id")
    private Integer assignedEmployeeId;

    @Column(name = "assigned_employee_name")
    private String assignedEmployeeName;

    @Column(name = "completion_notes", length = 2000)
    private String completionNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "preempted_by_task_id")
    private Integer preemptedByTaskId;

    @Column(name = "preempted_by_task_title")
    private String preemptedByTaskTitle;

    @Column(name = "preemption_message", length = 1000)
    private String preemptionMessage;

    @Column(name = "preemption_count")
    private Integer preemptionCount = 0;

    public TaskItem() {}

    public TaskItem(int id, String title, String requiredSkill,
                    String priority, int durationHours,
                    String deadline, String status) {
        this.id = id;
        this.title = title;
        this.requiredSkill = requiredSkill;
        this.priority = priority;
        this.durationHours = durationHours;
        this.remainingDurationHours = durationHours;
        this.deadline = deadline;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRequiredSkill() { return requiredSkill; }
    public void setRequiredSkill(String requiredSkill) {
        this.requiredSkill = requiredSkill;
    }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public int getDurationHours() { return durationHours != null ? durationHours : 2; }
    public void setDurationHours(Integer durationHours) {
        this.durationHours = (durationHours != null) ? durationHours : 2;
        if (this.remainingDurationHours == null) {
            this.remainingDurationHours = this.durationHours;
        }
    }

    public int getRemainingDurationHours() {
        return remainingDurationHours != null ? remainingDurationHours : getDurationHours();
    }
    public void setRemainingDurationHours(Integer remainingDurationHours) {
        this.remainingDurationHours = remainingDurationHours;
    }

    public int getProgressPercentage() {
        return progressPercentage != null ? progressPercentage : 0;
    }
    public void setProgressPercentage(Integer progressPercentage) {
        this.progressPercentage = (progressPercentage != null) ? progressPercentage : 0;
    }

    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }

    public void setDeadline(LocalDate deadlineDate) {
        this.deadline = (deadlineDate != null) ? deadlineDate.toString() : null;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getAssignedEmployeeId() { return assignedEmployeeId; }
    public void setAssignedEmployeeId(Integer assignedEmployeeId) {
        this.assignedEmployeeId = assignedEmployeeId;
    }

    public String getAssignedEmployeeName() { return assignedEmployeeName; }
    public void setAssignedEmployeeName(String assignedEmployeeName) {
        this.assignedEmployeeName = assignedEmployeeName;
    }

    public String getCompletionNotes() { return completionNotes; }
    public void setCompletionNotes(String completionNotes) { this.completionNotes = completionNotes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getPausedAt() { return pausedAt; }
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }

    public LocalDateTime getResumedAt() { return resumedAt; }
    public void setResumedAt(LocalDateTime resumedAt) { this.resumedAt = resumedAt; }

    public Integer getPreemptedByTaskId() { return preemptedByTaskId; }
    public void setPreemptedByTaskId(Integer preemptedByTaskId) { this.preemptedByTaskId = preemptedByTaskId; }

    public String getPreemptedByTaskTitle() { return preemptedByTaskTitle; }
    public void setPreemptedByTaskTitle(String preemptedByTaskTitle) { this.preemptedByTaskTitle = preemptedByTaskTitle; }

    public String getPreemptionMessage() { return preemptionMessage; }
    public void setPreemptionMessage(String preemptionMessage) { this.preemptionMessage = preemptionMessage; }

    public int getPreemptionCount() { return preemptionCount != null ? preemptionCount : 0; }
    public void setPreemptionCount(Integer preemptionCount) { this.preemptionCount = preemptionCount != null ? preemptionCount : 0; }

    @Transient
    public String getDeadlineRisk() {
        if ("Completed".equalsIgnoreCase(status)) {
            return "On Track";
        }
        if (deadline == null || deadline.isBlank()) {
            return "On Track";
        }
        try {
            LocalDate dl = LocalDate.parse(deadline.trim());
            LocalDate today = LocalDate.now();
            if (dl.isBefore(today)) {
                return "Overdue";
            }
            long days = ChronoUnit.DAYS.between(today, dl);
            long workingHoursAvailable = Math.max(1, days) * 8;
            int remaining = (remainingDurationHours != null && remainingDurationHours > 0)
                    ? remainingDurationHours : (durationHours != null ? durationHours : 2);
            if (remaining > workingHoursAvailable || (days <= 1 && remaining > 4)) {
                return "At Risk";
            }
            return "On Track";
        } catch (Exception e) {
            return "On Track";
        }
    }
}
