package com.taskengine.model;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String email;

    private String role;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "employee_skills",
            joinColumns = @JoinColumn(name = "employee_id")
    )
    @Column(name = "skill")
    private List<String> skills = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "employee_skill_proficiencies",
            joinColumns = @JoinColumn(name = "employee_id")
    )
    @MapKeyColumn(name = "skill_name")
    @Column(name = "proficiency_level")
    private Map<String, String> skillProficiencies = new HashMap<>();

    @Column(name = "is_available")
    private boolean available = true;

    @Column(name = "workload_hours")
    private Integer workloadHours = 0;

    @Column(name = "max_workload")
    private Integer maxWorkload = 40; // default max capacity in hours

    @Column(name = "priority")
    private Integer priority = 1; // Employee priority (higher value = higher priority)

    @Column(name = "performance")
    private Double performance = 5.0; // Performance rating (e.g. 1.0 - 5.0)

    @Version
    private Long version = 0L;

    public Employee() {}

    // ----- ID -----
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    // ----- Name -----
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // ----- Email -----
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    // ----- Role -----
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    // ----- Skills -----
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) {
        this.skills = (skills == null) ? new ArrayList<>() : skills;
    }

    // ----- Skill Proficiencies -----
    public Map<String, String> getSkillProficiencies() {
        if (skillProficiencies == null) skillProficiencies = new HashMap<>();
        return skillProficiencies;
    }

    public void setSkillProficiencies(Map<String, String> skillProficiencies) {
        this.skillProficiencies = (skillProficiencies != null) ? skillProficiencies : new HashMap<>();
        if (this.skills == null) this.skills = new ArrayList<>();
        for (String s : this.skillProficiencies.keySet()) {
            if (!this.skills.contains(s)) {
                this.skills.add(s);
            }
        }
    }

    public String getProficiencyForSkill(String skillName) {
        if (skillName == null || skillProficiencies == null) return "Intermediate";
        for (Map.Entry<String, String> entry : skillProficiencies.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(skillName.trim())) {
                return entry.getValue();
            }
        }
        return "Intermediate";
    }

    public void setProficiency(String skill, String level) {
        if (skillProficiencies == null) skillProficiencies = new HashMap<>();
        if (skills == null) skills = new ArrayList<>();
        skillProficiencies.put(skill, level);
        if (!skills.contains(skill)) {
            skills.add(skill);
        }
    }

    // ----- Availability -----
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    // ----- Workload -----
    public int getWorkloadHours() { return workloadHours != null ? workloadHours : 0; }
    public void setWorkloadHours(Integer workloadHours) { this.workloadHours = (workloadHours != null) ? workloadHours : 0; }

    // ----- Max Workload -----
    public int getMaxWorkload() { return maxWorkload != null ? maxWorkload : 40; }
    public void setMaxWorkload(Integer maxWorkload) { this.maxWorkload = (maxWorkload != null) ? maxWorkload : 40; }

    // ----- Priority -----
    public int getPriority() { return priority != null ? priority : 1; }
    public void setPriority(Integer priority) { this.priority = (priority != null) ? priority : 1; }

    // ----- Performance -----
    public double getPerformance() { return performance != null ? performance : 5.0; }
    public void setPerformance(Double performance) { this.performance = (performance != null) ? performance : 5.0; }

    // ----- Version -----
    public Long getVersion() { return version != null ? version : 0L; }
    public void setVersion(Long version) { this.version = (version != null) ? version : 0L; }

    // ----- Active Task Count -----
    @Transient
    private Integer activeTaskCount = 0;

    public int getActiveTaskCount() {
        return activeTaskCount != null ? activeTaskCount : 0;
    }

    public void setActiveTaskCount(Integer activeTaskCount) {
        this.activeTaskCount = (activeTaskCount != null) ? activeTaskCount : 0;
    }

    // ----- Username (matches login UserAccount) -----
    @Transient
    public String getUsername() {
        return email != null ? email.trim().toLowerCase() : null;
    }
}
