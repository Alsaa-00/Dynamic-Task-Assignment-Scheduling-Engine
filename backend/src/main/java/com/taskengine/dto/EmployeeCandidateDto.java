package com.taskengine.dto;

public class EmployeeCandidateDto {
    private int employeeId;
    private String name;
    private int workloadHours;
    private int maxWorkload;
    private String reason;
    private boolean selected;

    // Weighted Suitability Scoring components (0 - 100)
    private double suitabilityScore;
    private double skillScore;
    private double workloadScore;
    private double availabilityScore;
    private double reliabilityScore;
    private String skillProficiency;

    public EmployeeCandidateDto() {}

    public EmployeeCandidateDto(int employeeId, String name, int workloadHours, int maxWorkload, String reason) {
        this.employeeId = employeeId;
        this.name = name;
        this.workloadHours = workloadHours;
        this.maxWorkload = maxWorkload;
        this.reason = reason;
        this.selected = false;
    }

    public EmployeeCandidateDto(int employeeId, String name, int workloadHours, int maxWorkload, String reason, boolean selected) {
        this.employeeId = employeeId;
        this.name = name;
        this.workloadHours = workloadHours;
        this.maxWorkload = maxWorkload;
        this.reason = reason;
        this.selected = selected;
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

    public int getWorkloadHours() {
        return workloadHours;
    }

    public void setWorkloadHours(int workloadHours) {
        this.workloadHours = workloadHours;
    }

    public int getMaxWorkload() {
        return maxWorkload;
    }

    public void setMaxWorkload(int maxWorkload) {
        this.maxWorkload = maxWorkload;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public double getSuitabilityScore() {
        return suitabilityScore;
    }

    public void setSuitabilityScore(double suitabilityScore) {
        this.suitabilityScore = Math.round(suitabilityScore * 10.0) / 10.0;
    }

    public double getSkillScore() {
        return skillScore;
    }

    public void setSkillScore(double skillScore) {
        this.skillScore = Math.round(skillScore * 10.0) / 10.0;
    }

    public double getWorkloadScore() {
        return workloadScore;
    }

    public void setWorkloadScore(double workloadScore) {
        this.workloadScore = Math.round(workloadScore * 10.0) / 10.0;
    }

    public double getAvailabilityScore() {
        return availabilityScore;
    }

    public void setAvailabilityScore(double availabilityScore) {
        this.availabilityScore = Math.round(availabilityScore * 10.0) / 10.0;
    }

    public double getReliabilityScore() {
        return reliabilityScore;
    }

    public void setReliabilityScore(double reliabilityScore) {
        this.reliabilityScore = Math.round(reliabilityScore * 10.0) / 10.0;
    }

    public String getSkillProficiency() {
        return skillProficiency;
    }

    public void setSkillProficiency(String skillProficiency) {
        this.skillProficiency = skillProficiency;
    }
}
