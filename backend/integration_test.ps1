# Comprehensive Integration & Verification Test Suite for TaskFlow Scheduling Engine
$ErrorActionPreference = "Stop"

$baseUrl = "http://localhost:8080/api"
$passed = 0
$failed = 0

function Assert-Condition($condition, $testName) {
    if ($condition) {
        Write-Host " [PASS] $testName" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host " [FAIL] $testName" -ForegroundColor Red
        $script:failed++
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " RUNNING COMPLETE INTEGRATION TEST SUITE - TASKFLOW ENGINE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. MANAGER LOGIN
try {
    $mgrLogin = Invoke-RestMethod -Uri "$baseUrl/auth/login" -Method Post -Body (@{ username="manager"; password="manager123" } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($mgrLogin.role -eq "MANAGER" -and $mgrLogin.authHeader -ne $null) "Manager Login authentication"
} catch {
    Assert-Condition $false "Manager Login authentication failed: $_"
}

# 2. EMPLOYEE LOGIN
try {
    $empLogin = Invoke-RestMethod -Uri "$baseUrl/auth/login" -Method Post -Body (@{ username="emp1@company.com"; password="employee123" } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($empLogin.role -eq "EMPLOYEE" -and $empLogin.employeeId -gt 0) "Employee Login authentication"
} catch {
    Assert-Condition $false "Employee Login authentication failed: $_"
}

# 3. EMPLOYEE CRUD
$testEmpId = 0
$uniqueTestEmail = "kavita_$([System.Guid]::NewGuid().ToString().Substring(0,8))@company.com"
try {
    $newEmp = @{
        name = "Kavita Rao"
        email = $uniqueTestEmail
        role = "Cloud Security Engineer"
        skills = @("Cybersecurity", "Java", "AWS")
        maxWorkload = 30
        available = $true
    } | ConvertTo-Json
    $createdEmp = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body $newEmp -ContentType "application/json"
    $testEmpId = $createdEmp.id
    Assert-Condition ($createdEmp.id -gt 0 -and $createdEmp.name -eq "Kavita Rao") "Employee Creation (POST /api/employees)"

    # Edit employee
    $updateEmp = @{
        name = "Kavita Rao"
        email = $uniqueTestEmail
        role = "Senior Cloud Security Engineer"
        skills = @("Cybersecurity", "Java", "AWS", "Kubernetes")
        maxWorkload = 35
        available = $true
    } | ConvertTo-Json
    $updatedEmp = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId" -Method Put -Body $updateEmp -ContentType "application/json"
    Assert-Condition ($updatedEmp.role -eq "Senior Cloud Security Engineer" -and $updatedEmp.maxWorkload -eq 35) "Employee Update (PUT /api/employees/{id})"

    # Toggle availability
    $toggled = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId/availability" -Method Patch -Body (@{ available = $false } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($toggled.available -eq $false) "Employee Toggle Availability to False"
    $toggledBack = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId/availability" -Method Patch -Body (@{ available = $true } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($toggledBack.available -eq $true) "Employee Toggle Availability to True"
} catch {
    Assert-Condition $false "Employee CRUD failed: $_"
}

# 4. TASK CRUD
$testTaskId1 = 0
$testTaskId2 = 0
try {
    $task1 = @{
        title = "Audit Cloud IAM Security Policies"
        description = "Review AWS IAM roles and least-privilege policies"
        requiredSkill = "Cybersecurity"
        durationHours = 5
        priority = "Critical"
        deadline = "2026-11-20"
    } | ConvertTo-Json
    $createdTask1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body $task1 -ContentType "application/json"
    $testTaskId1 = $createdTask1.id
    Assert-Condition ($createdTask1.id -gt 0 -and $createdTask1.status -eq "Pending") "Task Creation 1 (POST /api/tasks)"

    $task2 = @{
        title = "Encrypt Microservice TLS In-Transit Traffic"
        description = "Configure mutual TLS with Kubernetes ingress"
        requiredSkill = "Cybersecurity"
        durationHours = 4
        priority = "High"
        deadline = "2026-11-22"
    } | ConvertTo-Json
    $createdTask2 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body $task2 -ContentType "application/json"
    $testTaskId2 = $createdTask2.id
    Assert-Condition ($createdTask2.id -gt 0) "Task Creation 2 (POST /api/tasks)"
} catch {
    Assert-Condition $false "Task CRUD failed: $_"
}

# 5. GREEDY HEURISTIC ALGORITHM EXECUTION & TIE-BREAKING
try {
    $assignResults = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post
    Assert-Condition ($assignResults.Count -gt 0) "Greedy Heuristic Execution (POST /api/assignments/run)"

    # Verify task 1 assigned to Kavita (only active employee with Cybersecurity skill)
    $refreshedTask1 = Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId1" -Method Get
    Assert-Condition ($refreshedTask1.assignedEmployeeId -eq $testEmpId -and $refreshedTask1.status -eq "Assigned") "Greedy Heuristic Skill Eligibility: Task assigned to matching skill employee"

    # Verify Kavita's sequential workload updated
    $refreshedEmp = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId" -Method Get
    Assert-Condition ($refreshedEmp.workloadHours -ge 5) "Sequential Workload Update: Workload increased immediately"
} catch {
    Assert-Condition $false "Greedy Heuristic test failed: $_"
}

# 6. TASK START & COMPLETION WITH WORKLOAD RECALCULATION
try {
    # Start task
    $startedTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId1/start" -Method Patch
    Assert-Condition ($startedTask.status -eq "In Progress" -and $startedTask.startedAt -ne $null) "Task Start (PATCH /api/tasks/{id}/start)"

    # Workload before completion
    $empBefore = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId" -Method Get
    $loadBefore = $empBefore.workloadHours

    # Mark completed with notes
    $completedTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId1/complete" -Method Patch -Body (@{ notes = "Security policies audited with 0 critical findings." } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($completedTask.status -eq "Completed" -and $completedTask.completedAt -ne $null -and $completedTask.completionNotes -ne $null) "Task Completion with Notes"

    # Workload after completion (must be reduced by 5 hours!)
    $empAfter = Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId" -Method Get
    $loadAfter = $empAfter.workloadHours
    Assert-Condition ($loadAfter -eq ($loadBefore - 5)) "Workload Recalculation: 5h duration removed from active workload upon completion ($loadBefore -> $loadAfter)"
} catch {
    Assert-Condition $false "Task Completion & Recalculation failed: $_"
}

# 7. EMERGENCY REPORTING & RESOLUTION WORKFLOW
$emergId = 0
try {
    $emergPayload = @{
        employeeId = $testEmpId
        type = "Technical issue"
        taskId = $testTaskId2
        description = "Laptop battery failure during power outage"
        expectedUnavailability = "3 hours"
    } | ConvertTo-Json
    $createdEmerg = Invoke-RestMethod -Uri "$baseUrl/emergencies" -Method Post -Body $emergPayload -ContentType "application/json"
    $emergId = $createdEmerg.id
    Assert-Condition ($createdEmerg.id -gt 0 -and $createdEmerg.status -eq "OPEN") "Emergency Submission by Employee"

    # Manager acknowledges
    $ackEmerg = Invoke-RestMethod -Uri "$baseUrl/emergencies/$emergId/acknowledge" -Method Patch
    Assert-Condition ($ackEmerg.status -eq "ACKNOWLEDGED" -and $ackEmerg.acknowledgedAt -ne $null) "Emergency Acknowledgment by Manager"

    # Manager resolves
    $resEmerg = Invoke-RestMethod -Uri "$baseUrl/emergencies/$emergId/resolve" -Method Patch -Body (@{ response = "Spare workstation assigned at office." } | ConvertTo-Json) -ContentType "application/json"
    Assert-Condition ($resEmerg.status -eq "RESOLVED" -and $resEmerg.managerResponse -ne $null) "Emergency Resolution by Manager with Note"
} catch {
    Assert-Condition $false "Emergency workflow failed: $_"
}

# 8. TASK REASSIGNMENT
try {
    # Find candidates for testTaskId2
    $cands = Invoke-RestMethod -Uri "$baseUrl/assignments/reassign-candidates/$testTaskId2" -Method Get
    Assert-Condition ($cands.PSObject.Properties['candidates'] -ne $null) "Fetch Reassignment Candidates via Greedy Heuristic"

    # Perform reassignment
    $reassignBody = @{
        taskId = $testTaskId2
        newEmployeeId = $testEmpId
        reason = "Transferred after emergency resolution"
    } | ConvertTo-Json
    $reassignedTask = Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body $reassignBody -ContentType "application/json"
    Assert-Condition ($reassignedTask.assignedEmployeeId -eq $testEmpId -and $reassignedTask.status -eq "Assigned") "Task Reassignment Execution"
} catch {
    Assert-Condition $false "Task Reassignment failed: $_"
}

# 9. ASSIGNMENT HISTORY & AUDIT PERSISTENCE
try {
    $historyList = Invoke-RestMethod -Uri "$baseUrl/assignments/history" -Method Get
    Assert-Condition ($historyList.Count -ge 2) "Assignment History Audit Logging (Found $($historyList.Count) records)"
} catch {
    Assert-Condition $false "Assignment History test failed: $_"
}

# 10. NOTIFICATION LISTING & MARK READ
try {
    $notifs = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get
    Assert-Condition ($notifs.Count -gt 0) "Persistent Notifications in Database (Found $($notifs.Count) notifications)"

    # Mark first read
    $firstNotif = $notifs[0]
    $readNotif = Invoke-RestMethod -Uri "$baseUrl/notifications/$($firstNotif.id)/read" -Method Patch
    Assert-Condition ($readNotif.read -eq $true) "Mark Notification as Read"
} catch {
    Assert-Condition $false "Notification test failed: $_"
}

# 11. BULK IMPORT TEMPLATE ENDPOINTS
try {
    $empTemplate = Invoke-RestMethod -Uri "$baseUrl/import/template/employees" -Method Get
    Assert-Condition ($empTemplate -like "*Name,Email,Role,Skills,Availability,MaxWorkload*") "Downloadable Employee CSV Template Endpoint"

    $taskTemplate = Invoke-RestMethod -Uri "$baseUrl/import/template/tasks" -Method Get
    Assert-Condition ($taskTemplate -like "*Title,Description,RequiredSkill,DurationHours,Priority,Deadline*") "Downloadable Task CSV Template Endpoint"
} catch {
    Assert-Condition $false "Template download failed: $_"
}

# 12. CLEANUP TEST ENTITIES
try {
    # Mark task 2 completed so employee can be cleanly deleted
    Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId2/complete" -Method Patch -Body (@{ notes = "Cleaned up" } | ConvertTo-Json) -ContentType "application/json" | Out-Null
    Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId1" -Method Delete | Out-Null
    Invoke-RestMethod -Uri "$baseUrl/tasks/$testTaskId2" -Method Delete | Out-Null
    Invoke-RestMethod -Uri "$baseUrl/employees/$testEmpId" -Method Delete | Out-Null
    Assert-Condition $true "Safe cleanup of test tasks and test employee"
} catch {
    Assert-Condition $false "Cleanup failed: $_"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " SUMMARY: $passed PASSED, $failed FAILED" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "==========================================================" -ForegroundColor Cyan
