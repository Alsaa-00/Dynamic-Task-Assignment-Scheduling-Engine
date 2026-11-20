package com.taskengine.model;

import com.taskengine.dto.EmployeeCandidateDto;
import com.taskengine.dto.EmployeeRejectionDto;

import java.util.ArrayList;
import java.util.List;

public class AssignmentResult {
    private int taskId;
    private String taskTitle;
    private String priority;
    private String requiredSkill;
    private int durationHours;
    private String deadline;
    private Integer employeeId;
    private String employeeName;
    private String status;
    private Integer workloadBefore;
    private Integer workloadAfter;
    private String reason;
    private String deadlineStatus;
    private List<EmployeeCandidateDto> eligibleCandidates = new ArrayList<>();
    private List<EmployeeRejectionDto> rejectedCandidates = new ArrayList<>();

    public AssignmentResult() {}

    public AssignmentResult(int taskId, String taskTitle, String employeeName, String status, String reason) {
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.employeeName = employeeName;
        this.status = status;
        this.reason = reason;
    }

    public AssignmentResult(int taskId, String taskTitle, String requiredSkill, int durationHours,
                            String deadline, Integer employeeId, String employeeName, String status,
                            Integer workloadBefore, Integer workloadAfter, String reason,
                            String deadlineStatus, List<EmployeeCandidateDto> eligibleCandidates,
                            List<EmployeeRejectionDto> rejectedCandidates) {
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.requiredSkill = requiredSkill;
        this.durationHours = durationHours;
        this.deadline = deadline;
        this.employeeId = employeeId;
        this.employeeName = employeeName;
        this.status = status;
        this.workloadBefore = workloadBefore;
        this.workloadAfter = workloadAfter;
        this.reason = reason;
        this.deadlineStatus = deadlineStatus;
        this.eligibleCandidates = eligibleCandidates != null ? eligibleCandidates : new ArrayList<>();
        this.rejectedCandidates = rejectedCandidates != null ? rejectedCandidates : new ArrayList<>();
    }

    public int getTaskId() {
        return taskId;
    }

    public void setTaskId(int taskId) {
        this.taskId = taskId;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getRequiredSkill() {
        return requiredSkill;
    }

    public void setRequiredSkill(String requiredSkill) {
        this.requiredSkill = requiredSkill;
    }

    public int getDurationHours() {
        return durationHours;
    }

    public void setDurationHours(int durationHours) {
        this.durationHours = durationHours;
    }

    public String getDeadline() {
        return deadline;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    public Integer getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Integer employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getWorkloadBefore() {
        return workloadBefore;
    }

    public void setWorkloadBefore(Integer workloadBefore) {
        this.workloadBefore = workloadBefore;
    }

    public Integer getWorkloadAfter() {
        return workloadAfter;
    }

    public void setWorkloadAfter(Integer workloadAfter) {
        this.workloadAfter = workloadAfter;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDeadlineStatus() {
        return deadlineStatus;
    }

    public void setDeadlineStatus(String deadlineStatus) {
        this.deadlineStatus = deadlineStatus;
    }

    public List<EmployeeCandidateDto> getEligibleCandidates() {
        return eligibleCandidates;
    }

    public void setEligibleCandidates(List<EmployeeCandidateDto> eligibleCandidates) {
        this.eligibleCandidates = eligibleCandidates;
    }

    public List<EmployeeRejectionDto> getRejectedCandidates() {
        return rejectedCandidates;
    }

    public void setRejectedCandidates(List<EmployeeRejectionDto> rejectedCandidates) {
        this.rejectedCandidates = rejectedCandidates;
    }
}
