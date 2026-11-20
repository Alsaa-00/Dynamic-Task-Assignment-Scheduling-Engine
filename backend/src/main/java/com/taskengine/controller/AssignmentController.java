package com.taskengine.controller;

import com.taskengine.model.Assignment;
import com.taskengine.model.AssignmentResult;
import com.taskengine.model.TaskItem;
import com.taskengine.repository.AssignmentRepository;
import com.taskengine.repository.TaskItemRepository;
import com.taskengine.service.AssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assignments")
@CrossOrigin(origins = "*")
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final TaskItemRepository taskRepository;
    private final AssignmentRepository assignmentRepository;

    public AssignmentController(AssignmentService assignmentService,
                                TaskItemRepository taskRepository,
                                AssignmentRepository assignmentRepository) {
        this.assignmentService = assignmentService;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @PostMapping("/run")
    public List<AssignmentResult> runAssignment() {
        return assignmentService.runAssignment();
    }

    @GetMapping
    public List<AssignmentResult> getAssignments() {
        List<AssignmentResult> results = new ArrayList<>();
        for (TaskItem task : taskRepository.findAll()) {
            if (task.getAssignedEmployeeId() != null) {
                results.add(new AssignmentResult(
                        task.getId(),
                        task.getTitle(),
                        task.getAssignedEmployeeName(),
                        task.getStatus(),
                        "Currently assigned to " + task.getAssignedEmployeeName()));
            }
        }
        return results;
    }

    @GetMapping("/history")
    public List<Assignment> getAssignmentHistory() {
        return assignmentRepository.findAllByOrderByAssignmentTimeDesc();
    }

    @GetMapping("/reassign-candidates/{taskId}")
    public ResponseEntity<Map<String, Object>> getReassignCandidates(@PathVariable int taskId) {
        return ResponseEntity.ok(assignmentService.findReassignCandidates(taskId));
    }

    @PostMapping("/reassign")
    public ResponseEntity<TaskItem> reassignTask(@RequestBody Map<String, Object> payload) {
        int taskId = Integer.parseInt(payload.get("taskId").toString());
        int newEmployeeId = Integer.parseInt(payload.get("newEmployeeId").toString());
        String reason = payload.get("reason") != null ? payload.get("reason").toString() : "Reassignment";

        TaskItem reassigned = assignmentService.reassignTask(taskId, newEmployeeId, reason);
        return ResponseEntity.ok(reassigned);
    }
}
