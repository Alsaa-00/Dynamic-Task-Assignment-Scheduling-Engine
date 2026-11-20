package com.taskengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized Configuration for the Weighted Assignment Heuristic.
 *
 * Scoring Formula:
 * Suitability Score = (skillWeight * Skill Score)
 *                   + (workloadWeight * Workload Score)
 *                   + (availabilityWeight * Availability Score)
 *                   + (reliabilityWeight * Reliability Score)
 *
 * All component scores are strictly normalized between 0.0 and 100.0.
 * Weights sum to 1.00.
 */
@Configuration
@ConfigurationProperties(prefix = "taskflow.assignment.weights")
public class AssignmentScoringConfig {

    public static final double DEFAULT_SKILL_WEIGHT = 0.40;
    public static final double DEFAULT_WORKLOAD_WEIGHT = 0.30;
    public static final double DEFAULT_AVAILABILITY_WEIGHT = 0.20;
    public static final double DEFAULT_RELIABILITY_WEIGHT = 0.10;

    private double skillWeight = DEFAULT_SKILL_WEIGHT;
    private double workloadWeight = DEFAULT_WORKLOAD_WEIGHT;
    private double availabilityWeight = DEFAULT_AVAILABILITY_WEIGHT;
    private double reliabilityWeight = DEFAULT_RELIABILITY_WEIGHT;

    public double getSkillWeight() {
        return skillWeight;
    }

    public void setSkillWeight(double skillWeight) {
        this.skillWeight = skillWeight;
    }

    public double getWorkloadWeight() {
        return workloadWeight;
    }

    public void setWorkloadWeight(double workloadWeight) {
        this.workloadWeight = workloadWeight;
    }

    public double getAvailabilityWeight() {
        return availabilityWeight;
    }

    public void setAvailabilityWeight(double availabilityWeight) {
        this.availabilityWeight = availabilityWeight;
    }

    public double getReliabilityWeight() {
        return reliabilityWeight;
    }

    public void setReliabilityWeight(double reliabilityWeight) {
        this.reliabilityWeight = reliabilityWeight;
    }
}
