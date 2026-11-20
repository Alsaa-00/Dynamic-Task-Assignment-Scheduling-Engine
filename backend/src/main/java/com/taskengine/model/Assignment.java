package com.taskengine.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "assignments")
public class Assignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "task_id", nullable = true)
    private TaskItem task;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id", nullable = true)
    private Employee employee;

    @Column(name = "task_id_val")
    private Integer taskId;

    @Column(name = "task_title")
    private String taskTitle;

    @Column(name = "employee_id_val")
    private Integer employeeId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "assignment_time", nullable = false)
    private LocalDateTime assignmentTime = LocalDateTime.now();

    @Column(name = "algorithm", nullable = false)
    private String algorithm = "Greedy Minimum Workload Heuristic";

    @Column(name = "workload_before")
    private Integer workloadBefore;

    @Column(name = "workload_after")
    private Integer workloadAfter;

    @Column(name = "outcome", nullable = false)
    private String outcome; // "SUCCESS" or "FAILED"

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "reassigned")
    private boolean reassigned = false;

    @Column(name = "prev_employee_id")
    private Integer previousEmployeeId;

    @Column(name = "prev_employee_name")
    private String previousEmployeeName;

    public Assignment() {}

    // ---------- getters & setters ----------
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TaskItem getTask() { return task; }
    public void setTask(TaskItem task) {
        this.task = task;
        if (task != null) {
            this.taskId = task.getId();
            this.taskTitle = task.getTitle();
        }
    }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) {
        this.employee = employee;
        if (employee != null) {
            this.employeeId = employee.getId();
            this.employeeName = employee.getName();
        }
    }

    public Integer getTaskId() { return taskId != null ? taskId : (task != null ? task.getId() : null); }
    public void setTaskId(Integer taskId) { this.taskId = taskId; }

    public String getTaskTitle() { return taskTitle != null ? taskTitle : (task != null ? task.getTitle() : null); }
    public void setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; }

    public Integer getEmployeeId() { return employeeId != null ? employeeId : (employee != null ? employee.getId() : null); }
    public void setEmployeeId(Integer employeeId) { this.employeeId = employeeId; }

    public String getEmployeeName() { return employeeName != null ? employeeName : (employee != null ? employee.getName() : null); }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

    public LocalDateTime getAssignmentTime() { return assignmentTime; }
    public void setAssignmentTime(LocalDateTime assignmentTime) { this.assignmentTime = assignmentTime; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public Integer getWorkloadBefore() { return workloadBefore; }
    public void setWorkloadBefore(Integer workloadBefore) { this.workloadBefore = workloadBefore; }

    public Integer getWorkloadAfter() { return workloadAfter; }
    public void setWorkloadAfter(Integer workloadAfter) { this.workloadAfter = workloadAfter; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public boolean isReassigned() { return reassigned; }
    public void setReassigned(boolean reassigned) { this.reassigned = reassigned; }

    public Integer getPreviousEmployeeId() { return previousEmployeeId; }
    public void setPreviousEmployeeId(Integer previousEmployeeId) { this.previousEmployeeId = previousEmployeeId; }

    public String getPreviousEmployeeName() { return previousEmployeeName; }
    public void setPreviousEmployeeName(String previousEmployeeName) { this.previousEmployeeName = previousEmployeeName; }
}
