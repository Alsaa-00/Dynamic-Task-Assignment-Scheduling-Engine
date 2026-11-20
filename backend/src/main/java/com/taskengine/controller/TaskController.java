package com.taskengine.controller;

import com.taskengine.model.TaskItem;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.service.AssignmentService;
import com.taskengine.service.NotificationService;
import com.taskengine.service.StreamService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class TaskController {

    private final TaskItemRepository taskRepository;
    private final AssignmentService assignmentService;
    private final NotificationService notificationService;
    private final StreamService streamService;
    private final com.taskengine.repository.AssignmentRepository assignmentRepository;
    private final com.taskengine.service.PreemptiveSchedulingService schedulingService;

    public TaskController(TaskItemRepository taskRepository,
                          AssignmentService assignmentService,
                          NotificationService notificationService,
                          StreamService streamService,
                          com.taskengine.repository.AssignmentRepository assignmentRepository,
                          com.taskengine.service.PreemptiveSchedulingService schedulingService) {
        this.taskRepository = taskRepository;
        this.assignmentService = assignmentService;
        this.notificationService = notificationService;
        this.streamService = streamService;
        this.assignmentRepository = assignmentRepository;
        this.schedulingService = schedulingService;
    }

    @GetMapping
    public List<TaskItem> getTasks(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String priority,
                                   @RequestParam(required = false) String skill,
                                   @RequestParam(required = false) String search) {
        List<TaskItem> list = taskRepository.findAll();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            if ("Overdue".equalsIgnoreCase(status)) {
                list = list.stream().filter(t -> "Overdue".equalsIgnoreCase(t.getDeadlineRisk()))
                        .collect(Collectors.toList());
            } else {
                list = list.stream().filter(t -> status.equalsIgnoreCase(t.getStatus()))
                        .collect(Collectors.toList());
            }
        }
        if (priority != null && !priority.isBlank() && !"all".equalsIgnoreCase(priority)) {
            list = list.stream().filter(t -> priority.equalsIgnoreCase(t.getPriority()))
                    .collect(Collectors.toList());
        }
        if (skill != null && !skill.isBlank() && !"all".equalsIgnoreCase(skill)) {
            list = list.stream().filter(t -> t.getRequiredSkill() != null && t.getRequiredSkill().equalsIgnoreCase(skill.trim()))
                    .collect(Collectors.toList());
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase().trim();
            list = list.stream().filter(t -> (t.getTitle() != null && t.getTitle().toLowerCase().contains(q))
                    || (t.getDescription() != null && t.getDescription().toLowerCase().contains(q))
                    || (t.getRequiredSkill() != null && t.getRequiredSkill().toLowerCase().contains(q))
                    || (t.getAssignedEmployeeName() != null && t.getAssignedEmployeeName().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }
        return list;
    }

    @GetMapping("/{id}")
    public TaskItem getTask(@PathVariable int id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));
    }

    @GetMapping("/employee/{employeeId}")
    public List<TaskItem> getTasksByEmployee(@PathVariable int employeeId) {
        return taskRepository.findByAssignedEmployeeId(employeeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskItem addTask(@RequestBody TaskItem task) {
        task.setId(0);
        if (task.getStatus() == null || task.getStatus().isBlank()) {
            task.setStatus("Pending");
        }
        task.setAssignedEmployeeId(null);
        task.setAssignedEmployeeName(null);
        task.setCreatedAt(LocalDateTime.now());
        if (task.getRemainingDurationHours() <= 0) {
            task.setRemainingDurationHours(task.getDurationHours());
        }
        TaskItem saved = taskRepository.save(task);

        // Preemptive priority comparison & scheduling evaluation
        schedulingService.evaluateAndSchedule(saved);

        notificationService.createNotification(null,
                "New task created: \"" + saved.getTitle() + "\" (" + saved.getRequiredSkill() + ", " + saved.getDurationHours() + "h, Priority: " + saved.getPriority() + ")",
                "INFO", saved.getId(), null);

        if ("At Risk".equalsIgnoreCase(saved.getDeadlineRisk())) {
            notificationService.createNotification(null,
                    "⚠️ Deadline Risk: Task \"" + saved.getTitle() + "\" is at risk of missing deadline (" + saved.getDeadline() + ").",
                    "ALERT", saved.getId(), null);
        }

        streamService.broadcastEvent("TASK_CREATED", saved);
        return saved;
    }

    @PutMapping("/{id}")
    public TaskItem updateTask(@PathVariable int id, @RequestBody TaskItem updated) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        String oldPriority = task.getPriority();
        task.setTitle(updated.getTitle());
        task.setDescription(updated.getDescription());
        task.setRequiredSkill(updated.getRequiredSkill());
        task.setPriority(updated.getPriority());
        int oldDuration = task.getDurationHours();
        task.setDurationHours(updated.getDurationHours());
        task.setDeadline(updated.getDeadline());

        TaskItem saved = taskRepository.save(task);

        // If priority changed, evaluate dynamic priority comparison
        if (oldPriority != null && !oldPriority.equalsIgnoreCase(saved.getPriority())) {
            schedulingService.evaluateAndSchedule(saved);
        }

        // If duration changed and task is assigned to an employee, update their workload
        if (oldDuration != updated.getDurationHours() && task.getAssignedEmployeeId() != null) {
            assignmentService.recalculateWorkload(task.getAssignedEmployeeId());
        }

        streamService.broadcastEvent("TASK_UPDATED", saved);
        return saved;
    }

    @PatchMapping("/{id}/start")
    public TaskItem startTask(@PathVariable int id) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        task.setStatus("In Progress");
        task.setStartedAt(LocalDateTime.now());
        if (task.getRemainingDurationHours() <= 0) {
            task.setRemainingDurationHours(task.getDurationHours());
        }
        TaskItem saved = taskRepository.save(task);

        notificationService.createNotification(null,
                "Task started: \"" + task.getTitle() + "\" by " + (task.getAssignedEmployeeName() != null ? task.getAssignedEmployeeName() : "Employee"),
                "INFO", task.getId(), task.getAssignedEmployeeId());

        streamService.broadcastEvent("TASK_UPDATED", saved);
        return saved;
    }

    @PatchMapping("/{id}/complete")
    public TaskItem completeTask(@PathVariable int id, @RequestBody(required = false) Map<String, String> body) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        task.setStatus("Completed");
        task.setCompletedAt(LocalDateTime.now());
        if (body != null && body.containsKey("notes")) {
            task.setCompletionNotes(body.get("notes"));
        }
        TaskItem saved = taskRepository.save(task);

        // Auto-resume previously paused task for this employee if any
        TaskItem resumed = schedulingService.onTaskCompleted(saved);

        // Recalculate employee active workload immediately
        if (task.getAssignedEmployeeId() != null) {
            assignmentService.recalculateWorkload(task.getAssignedEmployeeId());
        }

        // Notify manager
        String empName = task.getAssignedEmployeeName() != null ? task.getAssignedEmployeeName() : "Employee";
        notificationService.createNotification(null,
                "✅ Task completed: \"" + task.getTitle() + "\" by " + empName +
                        (task.getCompletionNotes() != null ? " (Notes: " + task.getCompletionNotes() + ")" : ""),
                "TASK_COMPLETED", task.getId(), task.getAssignedEmployeeId());

        streamService.broadcastEvent("TASK_COMPLETED", saved);
        return saved;
    }

    @PatchMapping("/{id}/status")
    public TaskItem updateStatus(@PathVariable int id, @RequestBody Map<String, String> body) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        String newStatus = body.get("status");
        if (newStatus != null && !newStatus.isBlank()) {
            task.setStatus(newStatus);
            if ("Completed".equalsIgnoreCase(newStatus)) {
                task.setCompletedAt(LocalDateTime.now());
            } else if ("In Progress".equalsIgnoreCase(newStatus)) {
                task.setStartedAt(LocalDateTime.now());
            }
        }
        TaskItem saved = taskRepository.save(task);

        if (task.getAssignedEmployeeId() != null) {
            assignmentService.recalculateWorkload(task.getAssignedEmployeeId());
        }

        streamService.broadcastEvent("TASK_UPDATED", saved);
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable int id) {
        TaskItem task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + id));

        Integer empId = task.getAssignedEmployeeId();

        // Nullify foreign key references in assignment audit records to allow safe deletion while preserving history
        List<com.taskengine.model.Assignment> assignments = assignmentRepository.findAll();
        for (com.taskengine.model.Assignment a : assignments) {
            if (a.getTask() != null && a.getTask().getId() == id) {
                a.setTask(null);
                assignmentRepository.save(a);
            }
        }

        taskRepository.delete(task);

        if (empId != null) {
            assignmentService.recalculateWorkload(empId);
        }

        streamService.broadcastEvent("TASK_DELETED", Map.of("id", id, "title", task.getTitle()));
    }
}
