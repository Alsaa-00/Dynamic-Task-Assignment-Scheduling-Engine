package com.taskengine.service;

import com.taskengine.model.Assignment;
import com.taskengine.model.Employee;
import com.taskengine.model.PreemptionRecord;
import com.taskengine.model.TaskItem;
import com.taskengine.repository.AssignmentRepository;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.PreemptionRecordRepository;
import com.taskengine.repository.TaskItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PreemptiveSchedulingService {

    private final TaskItemRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final PreemptionRecordRepository preemptionRepository;
    private final AssignmentRepository assignmentRepository;
    private final NotificationService notificationService;
    private final StreamService streamService;

    public PreemptiveSchedulingService(TaskItemRepository taskRepository,
                                       EmployeeRepository employeeRepository,
                                       PreemptionRecordRepository preemptionRepository,
                                       AssignmentRepository assignmentRepository,
                                       NotificationService notificationService,
                                       StreamService streamService) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
        this.preemptionRepository = preemptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    /**
     * Priority Hierarchy:
     * Critical (0) > High (1) > Medium (2) > Low (3)
     */
    public int priorityRank(String priority) {
        if (priority == null) return 2; // Default Medium
        switch (priority.trim().toUpperCase()) {
            case "CRITICAL": return 0;
            case "HIGH": return 1;
            case "MEDIUM": return 2;
            case "LOW": return 3;
            default: return 4;
        }
    }

    public boolean isHigherPriority(String p1, String p2) {
        return priorityRank(p1) < priorityRank(p2);
    }

    /**
     * Evaluates a new or pending task for Priority-Based Preemptive Scheduling.
     * 1. Checks all eligibility constraints (Skill, Availability, Workload Capacity, Deadline Feasibility).
     * 2. Selects best employee using Greedy Minimum Workload.
     * 3. If employee is executing a lower-priority task, pauses it (preserving progress & remaining time)
     *    and starts the higher-priority task immediately.
     * 4. If current task has equal or higher priority, does not interrupt (queues task as Assigned).
     * 5. If no employee is eligible, retains task as Pending/Unassigned and notifies manager.
     */
    @Transactional
    public Map<String, Object> evaluateAndSchedule(TaskItem task) {
        Map<String, Object> result = new HashMap<>();
        List<Employee> allEmployees = employeeRepository.findAll();
        List<TaskItem> allTasks = taskRepository.findAll();

        String requiredSkill = task.getRequiredSkill() != null ? task.getRequiredSkill().trim() : "";
        int duration = task.getDurationHours();
        LocalDate deadlineDate = parseLocalDate(task.getDeadline());
        LocalDate today = LocalDate.now();

        // 1. Check deadline validity
        boolean isDeadlineExpired = (deadlineDate != null && deadlineDate.isBefore(today));
        int availableWorkingHours = Integer.MAX_VALUE;
        if (deadlineDate != null && !isDeadlineExpired) {
            long daysRemaining = ChronoUnit.DAYS.between(today, deadlineDate);
            availableWorkingHours = (int) (Math.max(1, daysRemaining) * 8);
        }

        if (isDeadlineExpired) {
            task.setStatus("Unassigned");
            taskRepository.save(task);
            String failMsg = "Deadline cannot be met: Task deadline has expired on " + deadlineDate;
            notificationService.createNotification(null,
                    "⚠️ Unassigned Task Alert: " + task.getPriority() + " task \"" + task.getTitle() + "\" expired on " + deadlineDate,
                    "ALERT", task.getId(), null);
            result.put("action", "UNASSIGNED");
            result.put("message", failMsg);
            result.put("task", task);
            return result;
        }

        // 2. Filter eligible employees based on:
        //    A. Required Skill Matching
        //    B. Employee Availability
        //    C. Workload Capacity
        //    D. Deadline Feasibility
        List<Employee> eligible = new ArrayList<>();
        for (Employee emp : allEmployees) {
            boolean hasSkill = emp.getSkills() != null && emp.getSkills().stream()
                    .anyMatch(s -> s.equalsIgnoreCase(requiredSkill));
            boolean available = emp.isAvailable();
            boolean withinCapacity = (emp.getWorkloadHours() + duration) <= emp.getMaxWorkload();

            boolean deadlineFeasible = true;
            if (deadlineDate != null) {
                if (duration > availableWorkingHours || (emp.getWorkloadHours() + duration) > availableWorkingHours) {
                    deadlineFeasible = false;
                }
            }

            if (hasSkill && available && withinCapacity && deadlineFeasible) {
                eligible.add(emp);
            }
        }

        // 3. No eligible employee scenario
        if (eligible.isEmpty()) {
            task.setStatus("Unassigned");
            task.setAssignedEmployeeId(null);
            task.setAssignedEmployeeName(null);
            taskRepository.save(task);

            String reason;
            boolean anySkill = allEmployees.stream().anyMatch(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(requiredSkill)));
            if (!anySkill) {
                reason = "No employee with the required skill [" + requiredSkill + "]";
            } else {
                boolean anyAvail = allEmployees.stream().filter(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(requiredSkill))).anyMatch(Employee::isAvailable);
                if (!anyAvail) {
                    reason = "Employee unavailable: skilled employees are currently marked unavailable";
                } else {
                    reason = "Insufficient workload capacity or deadline infeasibility";
                }
            }

            notificationService.createNotification(null,
                    "⚠️ Unassigned Task Alert: " + task.getPriority() + " task \"" + task.getTitle() + "\" could not be assigned: " + reason,
                    "ALERT", task.getId(), null);

            streamService.broadcastEvent("TASK_UNASSIGNED", Map.of(
                    "taskId", task.getId(),
                    "title", task.getTitle(),
                    "priority", task.getPriority(),
                    "reason", reason
            ));

            result.put("action", "UNASSIGNED");
            result.put("message", reason);
            result.put("task", task);
            return result;
        }

        // 4. Check if any eligible employee is currently executing a lower-priority task to preempt
        List<Employee> preemptionCandidates = new ArrayList<>();
        Map<Integer, TaskItem> executingTaskMap = new HashMap<>();

        for (Employee emp : eligible) {
            TaskItem executing = taskRepository.findByAssignedEmployeeId(emp.getId()).stream()
                    .filter(t -> "In Progress".equalsIgnoreCase(t.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (executing != null && isHigherPriority(task.getPriority(), executing.getPriority())) {
                preemptionCandidates.add(emp);
                executingTaskMap.put(emp.getId(), executing);
            }
        }

        if (!preemptionCandidates.isEmpty()) {
            // Select candidate with minimum workload and deterministic tie-breakers
            preemptionCandidates.sort((a, b) -> compareCandidates(a, b, allTasks));
            Employee selected = preemptionCandidates.get(0);
            TaskItem currentExecuting = executingTaskMap.get(selected.getId());

            int workloadBefore = selected.getWorkloadHours();
            int workloadAfter = workloadBefore + duration;
            selected.setWorkloadHours(workloadAfter);
            employeeRepository.save(selected);

            // PREEMPTION OCCURS: High/Critical preempts Medium/Low
            pauseExecutingTask(currentExecuting, task);

            // Assign and immediately start the higher priority task
            task.setAssignedEmployeeId(selected.getId());
            task.setAssignedEmployeeName(selected.getName());
            task.setStatus("In Progress");
            task.setStartedAt(LocalDateTime.now());
            task.setRemainingDurationHours(duration);
            task.setProgressPercentage(0);
            TaskItem savedNewTask = taskRepository.save(task);

            String preemptionMsg = String.format("%s-priority task assigned. %s-priority task paused and will resume after completion.",
                    task.getPriority(), currentExecuting.getPriority());

            // Save Preemption Audit Record
            PreemptionRecord pr = new PreemptionRecord(
                    savedNewTask.getId(), savedNewTask.getTitle(), savedNewTask.getPriority(),
                    currentExecuting.getId(), currentExecuting.getTitle(), currentExecuting.getPriority(),
                    selected.getId(), selected.getName(),
                    currentExecuting.getRemainingDurationHours(),
                    currentExecuting.getProgressPercentage(),
                    preemptionMsg
            );
            preemptionRepository.save(pr);

            // Record in Assignment History
            Assignment assignment = new Assignment();
            assignment.setTask(savedNewTask);
            assignment.setEmployee(selected);
            assignment.setTaskId(savedNewTask.getId());
            assignment.setTaskTitle(savedNewTask.getTitle());
            assignment.setEmployeeId(selected.getId());
            assignment.setEmployeeName(selected.getName());
            assignment.setAssignmentTime(LocalDateTime.now());
            assignment.setAlgorithm("Priority-Based Preemptive Scheduling");
            assignment.setWorkloadBefore(workloadBefore);
            assignment.setWorkloadAfter(workloadAfter);
            assignment.setOutcome("PREEMPTED");
            assignment.setReason("Preempted lower-priority task #" + currentExecuting.getId() + " (\"" + currentExecuting.getTitle() + "\", " + currentExecuting.getPriority() + "). " + preemptionMsg);
            assignmentRepository.save(assignment);

            // Notify Employee
            notificationService.createNotification(selected.getId(),
                    "⚠️ Task \"" + currentExecuting.getTitle() + "\" (" + currentExecuting.getPriority() + ") has been PAUSED. Preempted by " + savedNewTask.getPriority() + " task \"" + savedNewTask.getTitle() + "\".",
                    "TASK_PAUSED", currentExecuting.getId(), selected.getId());

            notificationService.createNotification(selected.getId(),
                    "🚀 High-priority task \"" + savedNewTask.getTitle() + "\" (" + savedNewTask.getPriority() + ") assigned and started immediately.",
                    "TASK_ASSIGNED", savedNewTask.getId(), selected.getId());

            // Notify Manager
            notificationService.createNotification(null,
                    "⚡ Preemption Alert: " + preemptionMsg + " (Employee: " + selected.getName() + ")",
                    "INFO", savedNewTask.getId(), selected.getId());

            streamService.broadcastEvent("TASK_PREEMPTED", Map.of(
                    "preemptingTaskId", savedNewTask.getId(),
                    "preemptingTitle", savedNewTask.getTitle(),
                    "preemptedTaskId", currentExecuting.getId(),
                    "preemptedTitle", currentExecuting.getTitle(),
                    "employeeName", selected.getName(),
                    "message", preemptionMsg
            ));

            result.put("action", "PREEMPTED");
            result.put("preemptedTask", currentExecuting);
            result.put("preemptingTask", savedNewTask);
            result.put("message", preemptionMsg);
            result.put("employee", selected);
            return result;

        } else {
            // NO PREEMPTION OCCURS:
            // No eligible employee is currently executing a lower-priority task.
            // Task remains Pending in the backlog for Greedy Heuristic Assignment.
            result.put("action", "PENDING");
            result.put("message", "No executing lower-priority task to preempt; task remains Pending for heuristic assignment.");
            result.put("task", task);
            return result;
        }
    }

    /**
     * Pauses an executing task, calculating and preserving its progress and remaining hours.
     */
    private void pauseExecutingTask(TaskItem task, TaskItem preemptingTask) {
        int initialDuration = task.getDurationHours();
        int remaining = task.getRemainingDurationHours();

        // Calculate progress from startedAt to now if available
        if (task.getStartedAt() != null) {
            long minutesElapsed = Math.max(1, Duration.between(task.getStartedAt(), LocalDateTime.now()).toMinutes());
            double hoursElapsed = minutesElapsed / 60.0;
            if (hoursElapsed > initialDuration * 0.9) {
                hoursElapsed = initialDuration * 0.5; // Cap at 50% to ensure task remaining work is preserved
            }
            int calculatedRemaining = (int) Math.max(1, Math.round(initialDuration - hoursElapsed));
            remaining = Math.min(remaining, calculatedRemaining);
        } else {
            remaining = Math.max(1, initialDuration / 2);
        }

        int progress = (int) Math.min(90, Math.round(((double) (initialDuration - remaining) / initialDuration) * 100));

        task.setRemainingDurationHours(remaining);
        task.setProgressPercentage(progress);
        task.setStatus("Paused");
        task.setPausedAt(LocalDateTime.now());
        task.setPreemptedByTaskId(preemptingTask.getId());
        task.setPreemptedByTaskTitle(preemptingTask.getTitle());
        task.setPreemptionCount(task.getPreemptionCount() + 1);
        task.setPreemptionMessage("High-priority task assigned. " + task.getPriority() + "-priority task paused and will resume after completion.");
        taskRepository.save(task);
    }

    /**
     * Handles task completion: Automatically resumes the highest-priority paused task for that employee.
     */
    @Transactional
    public TaskItem onTaskCompleted(TaskItem completedTask) {
        Integer empId = completedTask.getAssignedEmployeeId();
        if (empId == null) return null;

        Employee employee = employeeRepository.findById(empId).orElse(null);
        if (employee == null) return null;

        // Find paused tasks for this employee
        List<TaskItem> pausedTasks = taskRepository.findByAssignedEmployeeId(empId).stream()
                .filter(t -> "Paused".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.toList());

        if (!pausedTasks.isEmpty()) {
            // Sort by Priority Queue: Priority (Critical > High > Medium > Low), then earliest deadline, then ID
            pausedTasks.sort(Comparator
                    .comparingInt((TaskItem t) -> priorityRank(t.getPriority()))
                    .thenComparing(t -> parseLocalDate(t.getDeadline()), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(TaskItem::getId));

            TaskItem taskToResume = pausedTasks.get(0);
            taskToResume.setStatus("In Progress");
            taskToResume.setResumedAt(LocalDateTime.now());
            taskToResume.setPreemptedByTaskId(null);
            taskToResume.setPreemptedByTaskTitle(null);
            taskToResume.setPreemptionMessage(null);
            TaskItem savedResumed = taskRepository.save(taskToResume);

            // Update preemption record
            Optional<PreemptionRecord> prOpt = preemptionRepository.findTopByPreemptedTaskIdOrderByPreemptedAtDesc(taskToResume.getId());
            if (prOpt.isPresent()) {
                PreemptionRecord pr = prOpt.get();
                pr.setStatus("RESUMED");
                pr.setResumedAt(LocalDateTime.now());
                preemptionRepository.save(pr);
            }

            // Notifications
            notificationService.createNotification(empId,
                    "▶️ Resumed Task: \"" + savedResumed.getTitle() + "\" (" + savedResumed.getPriority() + ") has been resumed from saved progress (" + savedResumed.getProgressPercentage() + "% complete, " + savedResumed.getRemainingDurationHours() + "h remaining).",
                    "TASK_RESUMED", savedResumed.getId(), empId);

            notificationService.createNotification(null,
                    "▶️ Task Resumed: \"" + savedResumed.getTitle() + "\" resumed on " + employee.getName() + " following completion of \"" + completedTask.getTitle() + "\".",
                    "INFO", savedResumed.getId(), empId);

            streamService.broadcastEvent("TASK_RESUMED", savedResumed);
            return savedResumed;

        } else {
            // No paused tasks. Check if employee has pending Assigned tasks to promote
            List<TaskItem> assignedQueue = taskRepository.findByAssignedEmployeeId(empId).stream()
                    .filter(t -> "Assigned".equalsIgnoreCase(t.getStatus()))
                    .sorted(Comparator
                            .comparingInt((TaskItem t) -> priorityRank(t.getPriority()))
                            .thenComparing(t -> parseLocalDate(t.getDeadline()), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparingInt(TaskItem::getId))
                    .collect(Collectors.toList());

            if (!assignedQueue.isEmpty()) {
                TaskItem nextTask = assignedQueue.get(0);
                nextTask.setStatus("In Progress");
                nextTask.setStartedAt(LocalDateTime.now());
                TaskItem savedNext = taskRepository.save(nextTask);

                notificationService.createNotification(empId,
                        "🚀 Starting next queued task: \"" + savedNext.getTitle() + "\" (" + savedNext.getPriority() + ")",
                        "TASK_STARTED", savedNext.getId(), empId);

                streamService.broadcastEvent("TASK_STARTED", savedNext);
                return savedNext;
            }
        }
        return null;
    }

    /**
     * Re-evaluates tasks when employee availability changes or emergency is resolved.
     */
    @Transactional
    public void onEmployeeAvailable(Employee emp) {
        if (!emp.isAvailable()) return;

        // Fetch pending/unassigned tasks ordered by Priority Queue
        List<TaskItem> pending = taskRepository.findAll().stream()
                .filter(t -> t.getAssignedEmployeeId() == null || "Pending".equalsIgnoreCase(t.getStatus()) || "Unassigned".equalsIgnoreCase(t.getStatus()))
                .sorted(Comparator
                        .comparingInt((TaskItem t) -> priorityRank(t.getPriority()))
                        .thenComparing(t -> parseLocalDate(t.getDeadline()), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(TaskItem::getId))
                .collect(Collectors.toList());

        for (TaskItem t : pending) {
            Map<String, Object> scheduleRes = evaluateAndSchedule(t);
            if ("ASSIGNED".equals(scheduleRes.get("action")) || "PREEMPTED".equals(scheduleRes.get("action"))) {
                break; // assigned task to newly available employee
            }
        }
    }

    /**
     * Returns full preemption status for manager and employee dashboards.
     */
    public Map<String, Object> getSchedulingStatus() {
        List<TaskItem> allTasks = taskRepository.findAll();
        List<Employee> allEmployees = employeeRepository.findAll();

        List<TaskItem> executing = allTasks.stream()
                .filter(t -> "In Progress".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.toList());

        List<TaskItem> paused = allTasks.stream()
                .filter(t -> "Paused".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.toList());

        List<TaskItem> assignedQueue = allTasks.stream()
                .filter(t -> "Assigned".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.toList());

        List<PreemptionRecord> history = preemptionRepository.findAllByOrderByPreemptedAtDesc();

        Map<String, Long> priorityDistribution = allTasks.stream()
                .filter(t -> !"Completed".equalsIgnoreCase(t.getStatus()))
                .collect(Collectors.groupingBy(
                        t -> t.getPriority() != null ? t.getPriority() : "Medium",
                        Collectors.counting()
                ));

        Map<String, Object> response = new HashMap<>();
        response.put("executingTasks", executing);
        response.put("pausedTasks", paused);
        response.put("assignedQueue", assignedQueue);
        response.put("preemptionHistory", history);
        response.put("totalPreemptions", history.size());
        response.put("priorityDistribution", priorityDistribution);
        response.put("activeEmployeesCount", (long) allEmployees.size());
        return response;
    }

    private int compareCandidates(Employee a, Employee b, List<TaskItem> allTasks) {
        int cmp = Integer.compare(a.getWorkloadHours(), b.getWorkloadHours());
        if (cmp != 0) return cmp;

        int aCommitments = (int) allTasks.stream().filter(t -> t.getAssignedEmployeeId() != null && t.getAssignedEmployeeId() == a.getId() && isActive(t.getStatus())).count();
        int bCommitments = (int) allTasks.stream().filter(t -> t.getAssignedEmployeeId() != null && t.getAssignedEmployeeId() == b.getId() && isActive(t.getStatus())).count();
        cmp = Integer.compare(aCommitments, bCommitments);
        if (cmp != 0) return cmp;

        cmp = Integer.compare(b.getPriority(), a.getPriority());
        if (cmp != 0) return cmp;

        cmp = Double.compare(b.getPerformance(), a.getPerformance());
        if (cmp != 0) return cmp;

        return Integer.compare(a.getId(), b.getId());
    }

    private boolean isActive(String status) {
        if (status == null) return false;
        return "In Progress".equalsIgnoreCase(status)
                || "Assigned".equalsIgnoreCase(status)
                || "Paused".equalsIgnoreCase(status)
                || "Emergency".equalsIgnoreCase(status);
    }

    private LocalDate parseLocalDate(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return LocalDate.parse(str.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
