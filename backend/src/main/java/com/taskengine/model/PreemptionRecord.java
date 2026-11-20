package com.taskengine.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "preemption_records")
public class PreemptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "preempting_task_id", nullable = false)
    private int preemptingTaskId;

    @Column(name = "preempting_task_title", nullable = false)
    private String preemptingTaskTitle;

    @Column(name = "preempting_task_priority", nullable = false)
    private String preemptingTaskPriority;

    @Column(name = "preempted_task_id", nullable = false)
    private int preemptedTaskId;

    @Column(name = "preempted_task_title", nullable = false)
    private String preemptedTaskTitle;

    @Column(name = "preempted_task_priority", nullable = false)
    private String preemptedTaskPriority;

    @Column(name = "employee_id", nullable = false)
    private int employeeId;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(name = "preempted_at", nullable = false)
    private LocalDateTime preemptedAt = LocalDateTime.now();

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(nullable = false)
    private String status = "PAUSED"; // PAUSED, RESUMED, COMPLETED

    @Column(name = "saved_remaining_hours")
    private int savedRemainingHours;

    @Column(name = "saved_progress_percentage")
    private int savedProgressPercentage;

    @Column(length = 1000)
    private String message;

    public PreemptionRecord() {}

    public PreemptionRecord(int preemptingTaskId, String preemptingTaskTitle, String preemptingTaskPriority,
                            int preemptedTaskId, String preemptedTaskTitle, String preemptedTaskPriority,
                            int employeeId, String employeeName, int savedRemainingHours,
                            int savedProgressPercentage, String message) {
        this.preemptingTaskId = preemptingTaskId;
        this.preemptingTaskTitle = preemptingTaskTitle;
        this.preemptingTaskPriority = preemptingTaskPriority;
        this.preemptedTaskId = preemptedTaskId;
        this.preemptedTaskTitle = preemptedTaskTitle;
        this.preemptedTaskPriority = preemptedTaskPriority;
        this.employeeId = employeeId;
        this.employeeName = employeeName;
        this.savedRemainingHours = savedRemainingHours;
        this.savedProgressPercentage = savedProgressPercentage;
        this.message = message;
        this.preemptedAt = LocalDateTime.now();
        this.status = "PAUSED";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getPreemptingTaskId() { return preemptingTaskId; }
    public void setPreemptingTaskId(int preemptingTaskId) { this.preemptingTaskId = preemptingTaskId; }

    public String getPreemptingTaskTitle() { return preemptingTaskTitle; }
    public void setPreemptingTaskTitle(String preemptingTaskTitle) { this.preemptingTaskTitle = preemptingTaskTitle; }

    public String getPreemptingTaskPriority() { return preemptingTaskPriority; }
    public void setPreemptingTaskPriority(String preemptingTaskPriority) { this.preemptingTaskPriority = preemptingTaskPriority; }

    public int getPreemptedTaskId() { return preemptedTaskId; }
    public void setPreemptedTaskId(int preemptedTaskId) { this.preemptedTaskId = preemptedTaskId; }

    public String getPreemptedTaskTitle() { return preemptedTaskTitle; }
    public void setPreemptedTaskTitle(String preemptedTaskTitle) { this.preemptedTaskTitle = preemptedTaskTitle; }

    public String getPreemptedTaskPriority() { return preemptedTaskPriority; }
    public void setPreemptedTaskPriority(String preemptedTaskPriority) { this.preemptedTaskPriority = preemptedTaskPriority; }

    public int getEmployeeId() { return employeeId; }
    public void setEmployeeId(int employeeId) { this.employeeId = employeeId; }

    public String getEmployeeName() { return employeeName; }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

    public LocalDateTime getPreemptedAt() { return preemptedAt; }
    public void setPreemptedAt(LocalDateTime preemptedAt) { this.preemptedAt = preemptedAt; }

    public LocalDateTime getResumedAt() { return resumedAt; }
    public void setResumedAt(LocalDateTime resumedAt) { this.resumedAt = resumedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSavedRemainingHours() { return savedRemainingHours; }
    public void setSavedRemainingHours(int savedRemainingHours) { this.savedRemainingHours = savedRemainingHours; }

    public int getSavedProgressPercentage() { return savedProgressPercentage; }
    public void setSavedProgressPercentage(int savedProgressPercentage) { this.savedProgressPercentage = savedProgressPercentage; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
