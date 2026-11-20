package com.taskengine.controller;

import com.taskengine.model.PreemptionRecord;
import com.taskengine.model.TaskItem;
import com.taskengine.repository.PreemptionRecordRepository;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.service.NotificationService;
import com.taskengine.service.PreemptiveSchedulingService;
import com.taskengine.service.StreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduling")
@CrossOrigin(origins = "*")
public class SchedulingController {

    private final PreemptiveSchedulingService schedulingService;
    private final PreemptionRecordRepository preemptionRepository;
    private final TaskItemRepository taskRepository;
    private final NotificationService notificationService;
    private final StreamService streamService;

    public SchedulingController(PreemptiveSchedulingService schedulingService,
                                PreemptionRecordRepository preemptionRepository,
                                TaskItemRepository taskRepository,
                                NotificationService notificationService,
                                StreamService streamService) {
        this.schedulingService = schedulingService;
        this.preemptionRepository = preemptionRepository;
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSchedulingStatus() {
        return ResponseEntity.ok(schedulingService.getSchedulingStatus());
    }

    @GetMapping("/preemptions")
    public ResponseEntity<List<PreemptionRecord>> getPreemptionHistory() {
        return ResponseEntity.ok(preemptionRepository.findAllByOrderByPreemptedAtDesc());
    }

    @GetMapping("/preemptions/employee/{employeeId}")
    public ResponseEntity<List<PreemptionRecord>> getEmployeePreemptions(@PathVariable int employeeId) {
        return ResponseEntity.ok(preemptionRepository.findByEmployeeIdOrderByPreemptedAtDesc(employeeId));
    }

    @PostMapping("/evaluate/{taskId}")
    public ResponseEntity<Map<String, Object>> evaluateTask(@PathVariable int taskId) {
        TaskItem task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + taskId));
        return ResponseEntity.ok(schedulingService.evaluateAndSchedule(task));
    }

    @PatchMapping("/tasks/{id}/pause")
    public ResponseEntity<TaskItem> pauseTask(@PathVariable int id, @RequestBody(required = false) Map<String, String> body) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        if (!"In Progress".equalsIgnoreCase(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only tasks In Progress can be paused");
        }

        task.setStatus("Paused");
        task.setPausedAt(LocalDateTime.now());
        String reason = (body != null && body.containsKey("reason")) ? body.get("reason") : "Manually paused by manager/employee";
        task.setPreemptionMessage(reason);
        TaskItem saved = taskRepository.save(task);

        notificationService.createNotification(task.getAssignedEmployeeId(),
                "Task \"" + task.getTitle() + "\" has been paused: " + reason,
                "TASK_PAUSED", task.getId(), task.getAssignedEmployeeId());

        streamService.broadcastEvent("TASK_UPDATED", saved);
        return ResponseEntity.ok(saved);
    }

    @PatchMapping("/tasks/{id}/resume")
    public ResponseEntity<TaskItem> resumeTask(@PathVariable int id) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        if (!"Paused".equalsIgnoreCase(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Paused tasks can be resumed");
        }

        task.setStatus("In Progress");
        task.setResumedAt(LocalDateTime.now());
        task.setPreemptionMessage(null);
        TaskItem saved = taskRepository.save(task);

        notificationService.createNotification(task.getAssignedEmployeeId(),
                "Task \"" + task.getTitle() + "\" has been resumed.",
                "TASK_RESUMED", task.getId(), task.getAssignedEmployeeId());

        streamService.broadcastEvent("TASK_RESUMED", saved);
        return ResponseEntity.ok(saved);
    }
}
