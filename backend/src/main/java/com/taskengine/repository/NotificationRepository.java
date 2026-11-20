package com.taskengine.repository;

import com.taskengine.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByEmployeeIdOrderByCreatedAtDesc(Integer employeeId);
    List<Notification> findByEmployeeIdIsNullOrderByCreatedAtDesc();
    List<Notification> findAllByOrderByCreatedAtDesc();
}
