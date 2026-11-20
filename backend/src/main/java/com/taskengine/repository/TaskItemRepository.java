package com.taskengine.repository;

import com.taskengine.model.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskItemRepository extends JpaRepository<TaskItem, Integer> {
    List<TaskItem> findByAssignedEmployeeId(Integer employeeId);
    List<TaskItem> findByStatus(String status);
    List<TaskItem> findByPriority(String priority);
    List<TaskItem> findByRequiredSkill(String requiredSkill);
}
