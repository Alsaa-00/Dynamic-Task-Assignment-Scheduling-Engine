package com.taskengine.repository;

import com.taskengine.model.EmergencyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmergencyReportRepository extends JpaRepository<EmergencyReport, Integer> {
    List<EmergencyReport> findByStatus(String status);
    List<EmergencyReport> findByStatusOrderByCreatedAtDesc(String status);
    List<EmergencyReport> findAllByOrderByCreatedAtDesc();
    List<EmergencyReport> findByEmployeeIdOrderByCreatedAtDesc(int employeeId);
}
