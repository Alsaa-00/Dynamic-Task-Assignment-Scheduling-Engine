package com.taskengine.repository;

import com.taskengine.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findAllByOrderByAssignmentTimeDesc();
    List<Assignment> findByTaskIdOrderByAssignmentTimeDesc(Integer taskId);
    List<Assignment> findByEmployeeIdOrderByAssignmentTimeDesc(Integer employeeId);
}
