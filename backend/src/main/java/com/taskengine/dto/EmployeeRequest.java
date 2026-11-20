package com.taskengine.dto;

import javax.validation.constraints.*;
import java.util.List;
import java.util.Map;

public class EmployeeRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Role is required")
    private String role;

    @NotEmpty(message = "At least one skill is required")
    private List<@NotBlank String> skills;

    private Map<String, String> skillProficiencies;

    private boolean available = true;

    @Min(value = 0, message = "Workload must be non‑negative")
    private int workloadHours = 0;

    @Min(value = 0, message = "Maximum workload must be non‑negative")
    private int maxWorkload = 40;

    // Getters & Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public Map<String, String> getSkillProficiencies() { return skillProficiencies; }
    public void setSkillProficiencies(Map<String, String> skillProficiencies) { this.skillProficiencies = skillProficiencies; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public int getWorkloadHours() { return workloadHours; }
    public void setWorkloadHours(int workloadHours) { this.workloadHours = workloadHours; }
    public int getMaxWorkload() { return maxWorkload; }
    public void setMaxWorkload(int maxWorkload) { this.maxWorkload = maxWorkload; }
}
