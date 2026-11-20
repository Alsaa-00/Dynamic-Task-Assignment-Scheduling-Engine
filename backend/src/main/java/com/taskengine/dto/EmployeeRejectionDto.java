package com.taskengine.dto;

public class EmployeeRejectionDto {
    private int employeeId;
    private String name;
    private String rejectionCategory;
    private String rejectionReason;

    public EmployeeRejectionDto() {}

    public EmployeeRejectionDto(int employeeId, String name, String rejectionCategory, String rejectionReason) {
        this.employeeId = employeeId;
        this.name = name;
        this.rejectionCategory = rejectionCategory;
        this.rejectionReason = rejectionReason;
    }

    public int getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(int employeeId) {
        this.employeeId = employeeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRejectionCategory() {
        return rejectionCategory;
    }

    public void setRejectionCategory(String rejectionCategory) {
        this.rejectionCategory = rejectionCategory;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}
