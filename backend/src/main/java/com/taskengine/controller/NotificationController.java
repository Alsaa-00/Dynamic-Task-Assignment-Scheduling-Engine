package com.taskengine.controller;

import com.taskengine.model.Notification;
import com.taskengine.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<Notification> getNotifications(@RequestParam(value = "employeeId", required = false) Integer employeeId) {
        return notificationService.getNotificationsForEmployee(employeeId);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Notification> markAsRead(@PathVariable Long id) {
        Notification n = notificationService.markAsRead(id);
        return ResponseEntity.ok(n);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, String>> markAllAsRead(@RequestParam(value = "employeeId", required = false) Integer employeeId) {
        notificationService.markAllAsRead(employeeId);
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read"));
    }
}
