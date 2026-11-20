package com.taskengine.service;

import com.taskengine.model.Notification;
import com.taskengine.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final StreamService streamService;

    public NotificationService(NotificationRepository notificationRepository, StreamService streamService) {
        this.notificationRepository = notificationRepository;
        this.streamService = streamService;
    }

    @Transactional
    public Notification createNotification(Integer employeeId, String message) {
        return createNotification(employeeId, message, "INFO", null, null);
    }

    @Transactional
    public Notification createNotification(Integer employeeId, String message, String type) {
        return createNotification(employeeId, message, type, null, null);
    }

    @Transactional
    public Notification createNotification(Integer employeeId, String message, String type, Integer relatedTaskId, Integer relatedEmployeeId) {
        Notification notification = new Notification(employeeId, message, type, relatedTaskId, relatedEmployeeId);
        Notification saved = notificationRepository.save(notification);
        try {
            streamService.publish(saved);
        } catch (Exception ignored) {}
        return saved;
    }

    @Transactional
    public Notification createGlobalNotification(String message) {
        return createNotification(null, message, "ALERT", null, null);
    }

    public List<Notification> getAllNotifications() {
        return notificationRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Notification> getNotificationsForEmployee(Integer employeeId) {
        if (employeeId == null) {
            return notificationRepository.findAllByOrderByCreatedAtDesc();
        }
        List<Notification> specific = notificationRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
        List<Notification> global = notificationRepository.findByEmployeeIdIsNullOrderByCreatedAtDesc();
        List<Notification> all = new ArrayList<>();
        all.addAll(specific);
        all.addAll(global);
        all.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return all;
    }

    @Transactional
    public Notification markAsRead(Long id) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        n.setRead(true);
        return notificationRepository.save(n);
    }

    @Transactional
    public void markAllAsRead(Integer employeeId) {
        List<Notification> notifications = getNotificationsForEmployee(employeeId);
        for (Notification n : notifications) {
            n.setRead(true);
        }
        notificationRepository.saveAll(notifications);
    }
}
