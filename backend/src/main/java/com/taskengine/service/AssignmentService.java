package com.taskengine.service;

import com.taskengine.config.AssignmentScoringConfig;
import com.taskengine.dto.EmployeeCandidateDto;
import com.taskengine.dto.EmployeeRejectionDto;
import com.taskengine.model.Assignment;
import com.taskengine.model.AssignmentResult;
import com.taskengine.model.Employee;
import com.taskengine.model.TaskItem;
import com.taskengine.repository.AssignmentRepository;
import com.taskengine.repository.EmployeeRepository;
import com.taskengine.repository.TaskItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AssignmentService {

    private final EmployeeRepository employeeRepository;
    private final TaskItemRepository taskRepository;
    private final AssignmentRepository assignmentRepository;
    private final NotificationService notificationService;
    private final StreamService streamService;
    private final AssignmentScoringConfig scoringConfig;

    @Autowired
    public AssignmentService(EmployeeRepository employeeRepository,
                             TaskItemRepository taskRepository,
                             AssignmentRepository assignmentRepository,
                             NotificationService notificationService,
                             StreamService streamService,
                             @Autowired(required = false) AssignmentScoringConfig scoringConfig) {
        this.employeeRepository = employeeRepository;
        this.taskRepository = taskRepository;
        this.assignmentRepository = assignmentRepository;
        this.notificationService = notificationService;
        this.streamService = streamService;
        this.scoringConfig = (scoringConfig != null) ? scoringConfig : new AssignmentScoringConfig();
    }

    /**
     * Executes the enhanced Greedy Minimum Workload Assignment Algorithm with constraint evaluations:
     * 1. Task processing order: Priority (Critical -> High -> Medium -> Low), earlier deadline first, Task ID ascending.
     * 2. Constraint evaluation for each pending task:
     *    A. Required Skill Matching (reject if skill mismatch)
     *    B. Employee Availability (reject if unavailable)
     *    C. Task Duration and Workload Capacity (reject if current workload + duration > max workload)
     *    D. Deadline Feasibility (reject if deadline expired or available working hours before deadline are insufficient)
     * 3. Greedy Employee Selection:
     *    - Minimum active workload
     *    - Deterministic multi-tier tie-breakers (task commitments, deadlines, priority, performance, lowest employee ID)
     *    - Immediate workload update and sequential recalculation before next task
     * 4. No Suitable Employee Scenario:
     *    - Keep status UNASSIGNED
     *    - Record exact failure reason (No employee with required skill, Employee unavailable, Insufficient capacity, Deadline cannot be met)
     *    - Create manager notification & real-time SSE alert
     */
    @Transactional
    public List<AssignmentResult> runAssignment() {
        List<AssignmentResult> results = new ArrayList<>();

        List<Employee> employees = employeeRepository.findAll();
        List<TaskItem> allTasks = taskRepository.findAll();

        // 1. Synchronize employee active workloads based on active tasks (Pending, In Progress, Assigned)
        Map<Integer, Integer> activeWorkloadMap = new HashMap<>();
        for (Employee emp : employees) {
            activeWorkloadMap.put(emp.getId(), 0);
        }
        for (TaskItem task : allTasks) {
            if (task.getAssignedEmployeeId() != null && isActiveStatus(task.getStatus())) {
                activeWorkloadMap.put(task.getAssignedEmployeeId(),
                        activeWorkloadMap.getOrDefault(task.getAssignedEmployeeId(), 0) + task.getDurationHours());
            }
        }
        for (Employee emp : employees) {
            emp.setWorkloadHours(activeWorkloadMap.getOrDefault(emp.getId(), 0));
        }

        // 2. Filter ONLY pending or unassigned tasks that have no assigned employee
        // Excludes Assigned, In Progress, Completed, Paused, and any already assigned tasks
        List<TaskItem> pendingTasks = allTasks.stream()
                .filter(t -> t.getAssignedEmployeeId() == null 
                        && ("Pending".equalsIgnoreCase(t.getStatus()) || "Unassigned".equalsIgnoreCase(t.getStatus())))
                .collect(Collectors.toList());

        // Sort pending tasks based on:
        // 1. Priority: Critical -> High -> Medium -> Low
        // 2. Earlier deadline first (null deadlines sorted last)
        // 3. Task ID ascending as final tie-breaker
        pendingTasks.sort((t1, t2) -> {
            int pr = Integer.compare(priorityRank(t1.getPriority()), priorityRank(t2.getPriority()));
            if (pr != 0) return pr;

            LocalDate d1 = parseLocalDate(t1.getDeadline());
            LocalDate d2 = parseLocalDate(t2.getDeadline());
            if (d1 != null && d2 != null) {
                int dc = d1.compareTo(d2);
                if (dc != 0) return dc;
            } else if (d1 != null) {
                return -1;
            } else if (d2 != null) {
                return 1;
            }

            return Integer.compare(t1.getId(), t2.getId());
        });

        int successCount = 0;
        int failedCount = 0;
        LocalDate today = LocalDate.now();

        // 3. Process tasks sequentially
        for (TaskItem task : pendingTasks) {
            String requiredSkill = task.getRequiredSkill() != null ? task.getRequiredSkill().trim() : "";
            int duration = task.getDurationHours();
            LocalDate deadlineDate = parseLocalDate(task.getDeadline());

            // Deadline evaluation
            boolean isDeadlineExpired = (deadlineDate != null && deadlineDate.isBefore(today));
            int availableWorkingHours = Integer.MAX_VALUE;
            if (deadlineDate != null && !isDeadlineExpired) {
                long daysRemaining = ChronoUnit.DAYS.between(today, deadlineDate);
                long effectiveDays = Math.max(1, daysRemaining);
                availableWorkingHours = (int) (effectiveDays * 8);
            }

            List<Employee> eligible = new ArrayList<>();
            List<EmployeeCandidateDto> eligibleCandidates = new ArrayList<>();
            List<EmployeeRejectionDto> rejectedCandidates = new ArrayList<>();

            // Evaluate all employees against constraints A, B, C, D
            for (Employee emp : employees) {
                boolean hasSkill = emp.getSkills() != null && emp.getSkills().stream()
                        .anyMatch(s -> s.equalsIgnoreCase(requiredSkill));
                boolean available = emp.isAvailable();
                boolean withinCapacity = (emp.getWorkloadHours() + duration) <= emp.getMaxWorkload();

                // Deadline feasibility check
                boolean deadlineFeasible = true;
                String deadlineRejectMsg = null;

                if (isDeadlineExpired) {
                    deadlineFeasible = false;
                    deadlineRejectMsg = "Deadline cannot be met: Task deadline has expired on " + deadlineDate;
                } else if (deadlineDate != null) {
                    if (duration > availableWorkingHours) {
                        deadlineFeasible = false;
                        deadlineRejectMsg = "Deadline cannot be met: Task requires " + duration + "h, but only " + availableWorkingHours + " working hours remain before " + deadlineDate;
                    } else if ((emp.getWorkloadHours() + duration) > availableWorkingHours) {
                        deadlineFeasible = false;
                        deadlineRejectMsg = "Deadline cannot be met: Employee has " + emp.getWorkloadHours() + "h existing load + " + duration + "h task, exceeding " + availableWorkingHours + "h working time available before " + deadlineDate;
                    }
                }

                // Constraint A: Required Skill Matching
                if (!hasSkill) {
                    rejectedCandidates.add(new EmployeeRejectionDto(
                            emp.getId(), emp.getName(), "SKILL_MISMATCH",
                            "Skill mismatch: lacks required skill [" + requiredSkill + "]"));
                }
                // Constraint B: Employee Availability
                else if (!available) {
                    rejectedCandidates.add(new EmployeeRejectionDto(
                            emp.getId(), emp.getName(), "UNAVAILABLE",
                            "Employee unavailable: marked as unavailable"));
                }
                // Constraint C: Task Duration & Workload Capacity
                else if (!withinCapacity) {
                    int remaining = Math.max(0, emp.getMaxWorkload() - emp.getWorkloadHours());
                    rejectedCandidates.add(new EmployeeRejectionDto(
                            emp.getId(), emp.getName(), "CAPACITY_EXCEEDED",
                            "Insufficient workload capacity: requires " + duration + "h, remaining capacity is " + remaining + "h (current load: " + emp.getWorkloadHours() + "h / " + emp.getMaxWorkload() + "h max)"));
                }
                // Constraint D: Deadline Feasibility
                else if (!deadlineFeasible) {
                    rejectedCandidates.add(new EmployeeRejectionDto(
                            emp.getId(), emp.getName(), "DEADLINE_INFEASIBLE",
                            deadlineRejectMsg));
                }
                // All constraints satisfied
                else {
                    eligible.add(emp);
                }
            }

            if (!eligible.isEmpty()) {
                // Weighted Assignment Heuristic: Calculate suitability score for each eligible employee
                List<CandidateScoringProfile> profiles = new ArrayList<>();
                for (Employee emp : eligible) {
                    profiles.add(computeScoringProfile(emp, requiredSkill, duration, allTasks));
                }

                // Rank eligible candidates:
                // 1. Highest Suitability Score (descending)
                // 2. Tie-breaker: Minimum current workload (ascending)
                // 3. Deterministic final tie-breaker: Lowest Employee ID (ascending)
                profiles.sort((p1, p2) -> {
                    int sc = Double.compare(p2.getTotalSuitabilityScore(), p1.getTotalSuitabilityScore());
                    if (sc != 0) return sc;
                    int wc = Integer.compare(p1.getEmployee().getWorkloadHours(), p2.getEmployee().getWorkloadHours());
                    if (wc != 0) return wc;
                    return Integer.compare(p1.getEmployee().getId(), p2.getEmployee().getId());
                });

                CandidateScoringProfile winner = profiles.get(0);
                Employee selected = winner.getEmployee();
                int workloadBefore = selected.getWorkloadHours();
                int workloadAfter = workloadBefore + duration;

                // Build rich comparative reasons for all eligible candidates
                eligibleCandidates.clear();
                for (CandidateScoringProfile p : profiles) {
                    Employee e = p.getEmployee();
                    boolean isWinner = (e.getId() == selected.getId());
                    String candidateReason;
                    if (isWinner) {
                        candidateReason = String.format("SELECTED: Highest Suitability Score (%.1f/100). [Matched %s (%s) · Skill: %.0f, Workload: %.0f, Avail: %.0f, Reliability: %.0f].",
                                p.getTotalSuitabilityScore(), requiredSkill, p.getProficiency(),
                                p.getSkillScore(), p.getWorkloadScore(), p.getAvailabilityScore(), p.getReliabilityScore());
                    } else {
                        if (p.getTotalSuitabilityScore() < winner.getTotalSuitabilityScore()) {
                            candidateReason = String.format("NOT SELECTED: Lower Suitability Score (%.1f vs %s's %.1f). [%s level, %dh active load].",
                                    p.getTotalSuitabilityScore(), selected.getName(), winner.getTotalSuitabilityScore(),
                                    p.getProficiency(), e.getWorkloadHours());
                        } else {
                            candidateReason = String.format("NOT SELECTED: Tied on Suitability Score (%.1f), but %s chosen by minimum workload tie-breaker (%dh vs %dh).",
                                    p.getTotalSuitabilityScore(), selected.getName(), selected.getWorkloadHours(), e.getWorkloadHours());
                        }
                    }

                    EmployeeCandidateDto dto = new EmployeeCandidateDto(
                            e.getId(), e.getName(), e.getWorkloadHours(), e.getMaxWorkload(),
                            candidateReason, isWinner);
                    dto.setSuitabilityScore(p.getTotalSuitabilityScore());
                    dto.setSkillScore(p.getSkillScore());
                    dto.setWorkloadScore(p.getWorkloadScore());
                    dto.setAvailabilityScore(p.getAvailabilityScore());
                    dto.setReliabilityScore(p.getReliabilityScore());
                    dto.setSkillProficiency(p.getProficiency());
                    eligibleCandidates.add(dto);
                }

                // Update workload immediately so subsequent tasks evaluate updated load
                selected.setWorkloadHours(workloadAfter);
                employeeRepository.save(selected);

                // Update task
                task.setAssignedEmployeeId(selected.getId());
                task.setAssignedEmployeeName(selected.getName());
                task.setStatus("Assigned");

                String deadlineStatus = (deadlineDate == null) ? "NO_DEADLINE" : "FEASIBLE";
                String reason = String.format("Assigned to %s with Highest Suitability Score (%.1f/100) [Skill: %.0f (%s), Workload: %.0f (%dh/%dh), Availability: %.0f, Reliability: %.0f (%s)]. Winner among %d eligible candidates.",
                        selected.getName(), winner.getTotalSuitabilityScore(),
                        winner.getSkillScore(), winner.getProficiency(),
                        winner.getWorkloadScore(), workloadBefore, selected.getMaxWorkload(),
                        winner.getAvailabilityScore(), winner.getReliabilityScore(),
                        winner.getReliabilityExplanation(), profiles.size());

                // Record Assignment history
                Assignment assignment = new Assignment();
                assignment.setTask(task);
                assignment.setEmployee(selected);
                assignment.setTaskId(task.getId());
                assignment.setTaskTitle(task.getTitle());
                assignment.setEmployeeId(selected.getId());
                assignment.setEmployeeName(selected.getName());
                assignment.setAssignmentTime(LocalDateTime.now());
                assignment.setAlgorithm("Weighted Multi-Criteria Suitability Heuristic");
                assignment.setWorkloadBefore(workloadBefore);
                assignment.setWorkloadAfter(workloadAfter);
                assignment.setOutcome("SUCCESS");
                assignment.setReason(reason);
                assignmentRepository.save(assignment);

                AssignmentResult assignedRes = new AssignmentResult(
                        task.getId(), task.getTitle(), requiredSkill, duration,
                        task.getDeadline(), selected.getId(), selected.getName(),
                        "Assigned", workloadBefore, workloadAfter, reason,
                        deadlineStatus, eligibleCandidates, rejectedCandidates);
                assignedRes.setPriority(task.getPriority() != null ? task.getPriority() : "Medium");
                results.add(assignedRes);
                successCount++;

            } else {
                // NO SUITABLE EMPLOYEE SCENARIO:
                // Diagnose exact failure category:
                // 1. Deadline cannot be met
                // 2. No employee with the required skill
                // 3. Employee unavailable
                // 4. Insufficient workload capacity
                String failureReason;
                String detail;
                String deadlineStatus;

                if (isDeadlineExpired) {
                    failureReason = "Deadline cannot be met";
                    detail = "Task deadline expired on " + deadlineDate + ". Tasks with expired deadlines cannot be assigned.";
                    deadlineStatus = "EXPIRED";
                } else {
                    List<Employee> withSkill = employees.stream()
                            .filter(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(requiredSkill)))
                            .collect(Collectors.toList());

                    List<Employee> availableWithSkill = withSkill.stream()
                            .filter(Employee::isAvailable)
                            .collect(Collectors.toList());

                    if (withSkill.isEmpty()) {
                        failureReason = "No employee with the required skill (Missing required skills)";
                        detail = "No employee in the organization possesses the required skill [" + requiredSkill + "].";
                        deadlineStatus = (deadlineDate == null) ? "NO_DEADLINE" : "FEASIBLE";
                    } else if (availableWithSkill.isEmpty()) {
                        failureReason = "Employee unavailable (Employee unavailability)";
                        detail = "Employees with skill [" + requiredSkill + "] exist, but all are currently marked unavailable.";
                        deadlineStatus = (deadlineDate == null) ? "NO_DEADLINE" : "FEASIBLE";
                    } else {
                        boolean allAtCapacity = availableWithSkill.stream()
                                .allMatch(e -> e.getWorkloadHours() >= e.getMaxWorkload());
                        if (allAtCapacity) {
                            failureReason = "Insufficient workload capacity (Maximum workload capacity reached)";
                            detail = "All available employees with skill [" + requiredSkill + "] have reached their maximum workload capacity.";
                            deadlineStatus = (deadlineDate == null) ? "NO_DEADLINE" : "FEASIBLE";
                        } else {
                            boolean anyHasCapacity = availableWithSkill.stream()
                                    .anyMatch(e -> (e.getWorkloadHours() + duration) <= e.getMaxWorkload());
                            if (!anyHasCapacity) {
                                failureReason = "Insufficient workload capacity (Insufficient remaining capacity)";
                                detail = "Available employees with skill [" + requiredSkill + "] do not have enough remaining capacity for this " + duration + "h task.";
                                deadlineStatus = (deadlineDate == null) ? "NO_DEADLINE" : "FEASIBLE";
                            } else {
                                failureReason = "Deadline cannot be met";
                                detail = "Available skilled employees do not have sufficient working hours before the deadline (" + deadlineDate + ") to complete the task.";
                                deadlineStatus = "INFEASIBLE";
                            }
                        }
                    }
                }

                // Unassigned tasks remain PENDING for manager review and future assignment runs
                task.setStatus("Pending");
                task.setAssignedEmployeeId(null);
                task.setAssignedEmployeeName(null);

                // Record failure history
                Assignment assignment = new Assignment();
                assignment.setTask(task);
                assignment.setTaskId(task.getId());
                assignment.setTaskTitle(task.getTitle());
                assignment.setAssignmentTime(LocalDateTime.now());
                assignment.setAlgorithm("Greedy Minimum Workload Heuristic");
                assignment.setOutcome("FAILED");
                assignment.setReason(failureReason + ": " + detail);
                assignmentRepository.save(assignment);

                AssignmentResult unassignedRes = new AssignmentResult(
                        task.getId(), task.getTitle(), requiredSkill, duration,
                        task.getDeadline(), null, null,
                        "Unassigned", null, null, failureReason + ": " + detail,
                        deadlineStatus, eligibleCandidates, rejectedCandidates);
                unassignedRes.setPriority(task.getPriority() != null ? task.getPriority() : "Medium");
                results.add(unassignedRes);
                failedCount++;
            }
        }

        // Persist updates ONLY for the processed pending tasks and employee workloads
        taskRepository.saveAll(pendingTasks);
        employeeRepository.saveAll(employees);

        // Generate ONE consolidated summary notification after processing completes
        String summaryMessage;
        if (pendingTasks.isEmpty()) {
            summaryMessage = "Automatic assignment run: No pending tasks found to process.";
        } else if (failedCount == 0) {
            summaryMessage = String.format("Automatic assignment completed successfully. Processed: %d | Assigned: %d | Remaining unassigned: 0",
                    pendingTasks.size(), successCount);
        } else {
            summaryMessage = String.format("Automatic assignment completed. Processed: %d | Assigned: %d | Remaining unassigned: %d",
                    pendingTasks.size(), successCount, failedCount);
        }
        notificationService.createNotification(null, summaryMessage, failedCount > 0 ? "WARNING" : "INFO", null, null);

        // Broadcast real-time update
        streamService.broadcastEvent("ASSIGNMENTS_RUN", Map.of(
                "successCount", successCount,
                "failedCount", failedCount,
                "totalProcessed", pendingTasks.size()
        ));

        return results;
    }

    /**
     * Recalculates and updates an employee's active workload based on active tasks in DB.
     */
    @Transactional
    public int recalculateWorkload(int employeeId) {
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        if (employee == null) return 0;

        List<TaskItem> tasks = taskRepository.findByAssignedEmployeeId(employeeId);
        int activeHours = tasks.stream()
                .filter(t -> isActiveStatus(t.getStatus()))
                .mapToInt(TaskItem::getDurationHours)
                .sum();

        employee.setWorkloadHours(activeHours);
        employeeRepository.save(employee);
        return activeHours;
    }

    /**
     * Finds candidate replacement employees for task reassignment using the enhanced Greedy Minimum Workload Heuristic.
     */
    public Map<String, Object> findReassignCandidates(int taskId) {
        TaskItem task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        List<Employee> allEmployees = employeeRepository.findAll();
        List<TaskItem> allTasks = taskRepository.findAll();
        String requiredSkill = task.getRequiredSkill() != null ? task.getRequiredSkill().trim() : "";
        Integer currentEmpId = task.getAssignedEmployeeId();
        int duration = task.getDurationHours();
        LocalDate deadlineDate = parseLocalDate(task.getDeadline());
        LocalDate today = LocalDate.now();

        boolean isDeadlineExpired = (deadlineDate != null && deadlineDate.isBefore(today));
        int availableWorkingHours = Integer.MAX_VALUE;
        if (deadlineDate != null && !isDeadlineExpired) {
            long daysRemaining = ChronoUnit.DAYS.between(today, deadlineDate);
            long effectiveDays = Math.max(1, daysRemaining);
            availableWorkingHours = (int) (effectiveDays * 8);
        }

        List<Employee> eligible = new ArrayList<>();
        if (!isDeadlineExpired && duration <= availableWorkingHours) {
            for (Employee e : allEmployees) {
                if (currentEmpId != null && e.getId() == currentEmpId) continue;
                if (!e.isAvailable()) continue;
                boolean hasSkill = e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(requiredSkill));
                if (!hasSkill) continue;
                if ((e.getWorkloadHours() + duration) > e.getMaxWorkload()) continue;
                if (deadlineDate != null && (e.getWorkloadHours() + duration) > availableWorkingHours) continue;
                eligible.add(e);
            }
            List<CandidateScoringProfile> profiles = new ArrayList<>();
            for (Employee e : eligible) {
                profiles.add(computeScoringProfile(e, requiredSkill, duration, allTasks));
            }
            profiles.sort((p1, p2) -> {
                int sc = Double.compare(p2.getTotalSuitabilityScore(), p1.getTotalSuitabilityScore());
                if (sc != 0) return sc;
                int wc = Integer.compare(p1.getEmployee().getWorkloadHours(), p2.getEmployee().getWorkloadHours());
                if (wc != 0) return wc;
                return Integer.compare(p1.getEmployee().getId(), p2.getEmployee().getId());
            });
            eligible.clear();
            for (CandidateScoringProfile p : profiles) {
                eligible.add(p.getEmployee());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("task", task);
        response.put("candidates", eligible);
        response.put("recommended", eligible.isEmpty() ? null : eligible.get(0));

        if (eligible.isEmpty()) {
            String failureReason;
            if (isDeadlineExpired) {
                failureReason = "Deadline cannot be met: Task deadline has expired";
            } else {
                List<Employee> withSkill = allEmployees.stream()
                        .filter(e -> currentEmpId == null || e.getId() != currentEmpId)
                        .filter(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.equalsIgnoreCase(requiredSkill)))
                        .collect(Collectors.toList());
                List<Employee> availableWithSkill = withSkill.stream().filter(Employee::isAvailable).collect(Collectors.toList());

                if (withSkill.isEmpty()) {
                    failureReason = "No employee with the required skill";
                } else if (availableWithSkill.isEmpty()) {
                    failureReason = "Employee unavailable";
                } else {
                    boolean anyHasCapacity = availableWithSkill.stream().anyMatch(e -> (e.getWorkloadHours() + duration) <= e.getMaxWorkload());
                    if (!anyHasCapacity) {
                        failureReason = "Insufficient workload capacity";
                    } else {
                        failureReason = "Deadline cannot be met";
                    }
                }
            }
            response.put("failureReason", failureReason);
        }

        return response;
    }

    /**
     * Reassigns a task to a new employee and recalculates both employees' workloads.
     */
    @Transactional
    public TaskItem reassignTask(int taskId, int newEmployeeId, String reason) {
        TaskItem task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        Employee newEmployee = employeeRepository.findById(newEmployeeId)
                .orElseThrow(() -> new IllegalArgumentException("Target employee not found: " + newEmployeeId));

        Integer oldEmployeeId = task.getAssignedEmployeeId();
        String oldEmployeeName = task.getAssignedEmployeeName();

        int oldWorkloadBefore = newEmployee.getWorkloadHours();
        int newWorkloadAfter = oldWorkloadBefore + task.getDurationHours();

        // Update task
        task.setAssignedEmployeeId(newEmployee.getId());
        task.setAssignedEmployeeName(newEmployee.getName());
        task.setStatus("Assigned");
        TaskItem savedTask = taskRepository.save(task);

        // Recalculate workloads for both
        recalculateWorkload(newEmployee.getId());
        if (oldEmployeeId != null && oldEmployeeId != newEmployeeId) {
            recalculateWorkload(oldEmployeeId);
        }

        // Record reassignment in history
        Assignment assignment = new Assignment();
        assignment.setTask(savedTask);
        assignment.setEmployee(newEmployee);
        assignment.setTaskId(savedTask.getId());
        assignment.setTaskTitle(savedTask.getTitle());
        assignment.setEmployeeId(newEmployee.getId());
        assignment.setEmployeeName(newEmployee.getName());
        assignment.setAssignmentTime(LocalDateTime.now());
        assignment.setAlgorithm("Greedy Minimum Workload Heuristic (Reassignment)");
        assignment.setWorkloadBefore(oldWorkloadBefore);
        assignment.setWorkloadAfter(newWorkloadAfter);
        assignment.setOutcome("SUCCESS");
        assignment.setReassigned(true);
        assignment.setPreviousEmployeeId(oldEmployeeId);
        assignment.setPreviousEmployeeName(oldEmployeeName);
        assignment.setReason("Reassigned: " + (reason != null ? reason : "Workforce adjustment"));
        assignmentRepository.save(assignment);

        // Notify new employee
        notificationService.createNotification(newEmployee.getId(),
                "Task reassigned to you: \"" + savedTask.getTitle() + "\" (" + savedTask.getDurationHours() + "h)",
                "REASSIGNMENT", savedTask.getId(), newEmployee.getId());

        // Notify previous employee
        if (oldEmployeeId != null) {
            notificationService.createNotification(oldEmployeeId,
                    "Task \"" + savedTask.getTitle() + "\" has been reassigned to " + newEmployee.getName(),
                    "INFO", savedTask.getId(), oldEmployeeId);
        }

        // Notify manager
        notificationService.createNotification(null,
                String.format("Task \"%s\" reassigned from %s to %s.",
                        savedTask.getTitle(),
                        (oldEmployeeName != null ? oldEmployeeName : "Unassigned"),
                        newEmployee.getName()),
                "INFO", savedTask.getId(), newEmployee.getId());

        streamService.broadcastEvent("TASK_REASSIGNED", savedTask);
        return savedTask;
    }

    /**
     * Enhanced Candidate Comparator:
     * 1. Minimum active workload (lowest workload preferred)
     * 2. Existing task commitments count (fewer active tasks preferred)
     * 3. Existing task deadlines (later earliest deadline preferred)
     * 4. Employee priority (higher priority preferred)
     * 5. Relevant employee performance (higher performance rating preferred)
     * 6. Deterministic final tie-breaker: Lowest Employee ID
     */
    public int compareCandidates(Employee a, Employee b, List<TaskItem> allTasks) {
        // Criterion 1: Minimum active workload
        int cmp = Integer.compare(a.getWorkloadHours(), b.getWorkloadHours());
        if (cmp != 0) return cmp;

        // Criterion 2: Fewer active commitments
        int aCommitments = getActiveCommitmentsCount(a.getId(), allTasks);
        int bCommitments = getActiveCommitmentsCount(b.getId(), allTasks);
        cmp = Integer.compare(aCommitments, bCommitments);
        if (cmp != 0) return cmp;

        // Criterion 3: Existing task deadlines
        LocalDate aEarliestDeadline = getEarliestActiveDeadline(a.getId(), allTasks);
        LocalDate bEarliestDeadline = getEarliestActiveDeadline(b.getId(), allTasks);
        cmp = compareDeadlines(aEarliestDeadline, bEarliestDeadline);
        if (cmp != 0) return cmp;

        // Criterion 4: Employee priority
        cmp = Integer.compare(b.getPriority(), a.getPriority());
        if (cmp != 0) return cmp;

        // Criterion 5: Employee performance
        cmp = Double.compare(b.getPerformance(), a.getPerformance());
        if (cmp != 0) return cmp;

        // Criterion 6: Deterministic final tie-breaker: Lowest Employee ID
        return Integer.compare(a.getId(), b.getId());
    }

    private String explainTieBreaker(Employee winner, Employee other, List<TaskItem> allTasks) {
        int wComm = getActiveCommitmentsCount(winner.getId(), allTasks);
        int oComm = getActiveCommitmentsCount(other.getId(), allTasks);
        if (wComm < oComm) {
            return String.format("%s has fewer active task commitments (%d vs %d).", winner.getName(), wComm, oComm);
        }

        LocalDate wDead = getEarliestActiveDeadline(winner.getId(), allTasks);
        LocalDate oDead = getEarliestActiveDeadline(other.getId(), allTasks);
        if (compareDeadlines(wDead, oDead) < 0) {
            return String.format("%s has more deadline runway on existing tasks.", winner.getName());
        }

        if (winner.getPriority() > other.getPriority()) {
            return String.format("%s has higher employee priority rank (%d vs %d).", winner.getName(), winner.getPriority(), other.getPriority());
        }

        if (winner.getPerformance() > other.getPerformance()) {
            return String.format("%s has higher performance score (%.1f vs %.1f).", winner.getName(), winner.getPerformance(), other.getPerformance());
        }

        return String.format("Resolved via tie-breaker rule: lower Employee ID (#%d vs #%d).", winner.getId(), other.getId());
    }

    private int getActiveCommitmentsCount(int employeeId, List<TaskItem> allTasks) {
        return (int) allTasks.stream()
                .filter(t -> t.getAssignedEmployeeId() != null 
                        && t.getAssignedEmployeeId() == employeeId 
                        && isActiveStatus(t.getStatus()))
                .count();
    }

    private LocalDate getEarliestActiveDeadline(int employeeId, List<TaskItem> allTasks) {
        return allTasks.stream()
                .filter(t -> t.getAssignedEmployeeId() != null 
                        && t.getAssignedEmployeeId() == employeeId 
                        && isActiveStatus(t.getStatus()) 
                        && t.getDeadline() != null)
                .map(t -> parseLocalDate(t.getDeadline()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    public LocalDate parseLocalDate(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return LocalDate.parse(str.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private int compareDeadlines(LocalDate d1, LocalDate d2) {
        if (d1 == null && d2 == null) return 0;
        if (d1 == null) return -1;
        if (d2 == null) return 1;
        return d2.compareTo(d1);
    }

    private boolean isActiveStatus(String status) {
        if (status == null) return false;
        return "Pending".equalsIgnoreCase(status)
                || "In Progress".equalsIgnoreCase(status)
                || "Assigned".equalsIgnoreCase(status)
                || "Paused".equalsIgnoreCase(status)
                || "Emergency".equalsIgnoreCase(status);
    }

    private int priorityRank(String priority) {
        if (priority == null) return 3;
        switch (priority.trim().toUpperCase()) {
            case "CRITICAL": return 0;
            case "HIGH": return 1;
            case "MEDIUM": return 2;
            case "LOW": return 3;
            default: return 4;
        }
    }

    /**
     * Candidate Scoring Profile for the Weighted Multi-Criteria Suitability Heuristic.
     */
    public static class CandidateScoringProfile {
        private final Employee employee;
        private final double skillScore;
        private final double workloadScore;
        private final double availabilityScore;
        private final double reliabilityScore;
        private final double totalSuitabilityScore;
        private final String proficiency;
        private final String reliabilityExplanation;

        public CandidateScoringProfile(Employee employee, double skillScore, double workloadScore,
                                       double availabilityScore, double reliabilityScore,
                                       double totalSuitabilityScore, String proficiency,
                                       String reliabilityExplanation) {
            this.employee = employee;
            this.skillScore = skillScore;
            this.workloadScore = workloadScore;
            this.availabilityScore = availabilityScore;
            this.reliabilityScore = reliabilityScore;
            this.totalSuitabilityScore = totalSuitabilityScore;
            this.proficiency = proficiency;
            this.reliabilityExplanation = reliabilityExplanation;
        }

        public Employee getEmployee() { return employee; }
        public double getSkillScore() { return skillScore; }
        public double getWorkloadScore() { return workloadScore; }
        public double getAvailabilityScore() { return availabilityScore; }
        public double getReliabilityScore() { return reliabilityScore; }
        public double getTotalSuitabilityScore() { return totalSuitabilityScore; }
        public String getProficiency() { return proficiency; }
        public String getReliabilityExplanation() { return reliabilityExplanation; }
    }

    /**
     * Calculates the normalized (0-100) Suitability Score for an employee against a task:
     * Suitability Score = (0.40 * Skill) + (0.30 * Workload) + (0.20 * Availability) + (0.10 * Reliability)
     */
    public CandidateScoringProfile computeScoringProfile(Employee emp, String requiredSkill, int duration, List<TaskItem> allTasks) {
        // 1. Skill Score (0 - 100): Based on proficiency level
        String proficiency = emp.getProficiencyForSkill(requiredSkill);
        double skillScore;
        if ("Advanced".equalsIgnoreCase(proficiency)) {
            skillScore = 100.0;
        } else if ("Beginner".equalsIgnoreCase(proficiency)) {
            skillScore = 50.0;
        } else {
            skillScore = 75.0; // Intermediate default
        }

        // 2. Workload Score (0 - 100): Lower current workload receives higher score
        double loadRatio = (double) emp.getWorkloadHours() / (double) Math.max(1, emp.getMaxWorkload());
        double workloadScore = Math.max(0.0, Math.min(100.0, (1.0 - loadRatio) * 100.0));

        // 3. Availability Score (0 - 100): Available remaining capacity margin
        int remainingMargin = emp.getMaxWorkload() - (emp.getWorkloadHours() + duration);
        double availabilityScore = Math.max(0.0, Math.min(100.0, ((double) remainingMargin / (double) Math.max(1, emp.getMaxWorkload())) * 100.0));

        // 4. Reliability Score (0 - 100): Based on task completion history, with clear fallback if no history
        long totalAssigned = allTasks.stream()
                .filter(t -> t.getAssignedEmployeeId() != null && t.getAssignedEmployeeId() == emp.getId())
                .count();
        long completed = allTasks.stream()
                .filter(t -> t.getAssignedEmployeeId() != null && t.getAssignedEmployeeId() == emp.getId() && "Completed".equalsIgnoreCase(t.getStatus()))
                .count();

        double reliabilityScore;
        String reliabilityExplanation;
        if (totalAssigned > 0) {
            double ratio = (double) completed / (double) totalAssigned;
            reliabilityScore = Math.min(100.0, 70.0 + (ratio * 30.0));
            reliabilityExplanation = String.format("%.0f%% completed (%d of %d past tasks finished)", ratio * 100.0, completed, totalAssigned);
        } else {
            // Clearly documented neutral fallback for employees without completed task history
            reliabilityScore = 75.0;
            reliabilityExplanation = "Baseline neutral reliability (75.0): No historical tasks assigned yet";
        }

        double total = (scoringConfig.getSkillWeight() * skillScore)
                     + (scoringConfig.getWorkloadWeight() * workloadScore)
                     + (scoringConfig.getAvailabilityWeight() * availabilityScore)
                     + (scoringConfig.getReliabilityWeight() * reliabilityScore);
        total = Math.round(total * 10.0) / 10.0;

        return new CandidateScoringProfile(emp, skillScore, workloadScore, availabilityScore, reliabilityScore, total, proficiency, reliabilityExplanation);
    }
}
