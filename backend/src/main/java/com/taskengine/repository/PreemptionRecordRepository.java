package com.taskengine.repository;

import com.taskengine.model.PreemptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PreemptionRecordRepository extends JpaRepository<PreemptionRecord, Integer> {
    List<PreemptionRecord> findAllByOrderByPreemptedAtDesc();
    List<PreemptionRecord> findByEmployeeIdOrderByPreemptedAtDesc(int employeeId);
    List<PreemptionRecord> findByPreemptedTaskId(int preemptedTaskId);
    List<PreemptionRecord> findByPreemptedTaskIdAndStatus(int preemptedTaskId, String status);
    Optional<PreemptionRecord> findTopByPreemptedTaskIdOrderByPreemptedAtDesc(int preemptedTaskId);
}
