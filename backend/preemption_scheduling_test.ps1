# Priority-Based Preemptive Scheduling Automated Test Suite
# Tests all 6 required scenarios:
# Scenario 1: Medium executing -> High added -> Medium paused -> High executes -> Medium resumes upon High completion
# Scenario 2: Low executing -> Critical added -> Low paused -> Critical executes
# Scenario 3: High executing -> Medium added -> High continues (No preemption)
# Scenario 4: High executing -> another High added -> current High continues (Equal priority does not preempt)
# Scenario 5: Employee lacks skill for Critical -> assign to another eligible employee
# Scenario 6: Higher-priority task added but no employee eligible -> retain as Pending/Unassigned & notify manager
# Status Endpoint Verification: GET /api/scheduling/status

$ErrorActionPreference = "Stop"
$baseUrl = "http://localhost:8080/api"
$authHeader = @{ "Authorization" = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("manager:manager123")) }
$jsonHeader = @{ 
    "Authorization" = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("manager:manager123"))
    "Content-Type"  = "application/json" 
}

function Assert-Condition($condition, $message) {
    if (-not $condition) {
        Write-Host " [FAIL] $message" -ForegroundColor Red
        throw "Assertion failed: $message"
    } else {
        Write-Host " [PASS] $message" -ForegroundColor Green
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "STARTING PRIORITY-BASED PREEMPTIVE SCHEDULING TEST SUITE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$testRunId = [guid]::NewGuid().ToString().Substring(0, 8)
Write-Host "Test Run ID: $testRunId"

# ==========================================================================
# Scenario 1: Medium executing -> High added -> Medium paused -> High executes
#             -> Medium resumes upon High completion
# ==========================================================================
Write-Host "`n--- Scenario 1: Medium Preempted by High & Resumed on Completion ---" -ForegroundColor Yellow

$skill1 = "PreemptSkill1_$testRunId"
$emp1Payload = @{
    name = "Emp_Preempt1_$testRunId"
    email = "emp_p1_$testRunId@test.com"
    role = "Senior Engineer"
    skills = @($skill1)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.8
} | ConvertTo-Json
$emp1 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp1Payload
Assert-Condition ($emp1.id -gt 0) "Created Employee 1 with ID #$($emp1.id)"

# Add Medium task
$medTaskPayload = @{
    title = "MediumTask_S1_$testRunId"
    description = "Executing normal medium priority task"
    requiredSkill = $skill1
    priority = "Medium"
    durationHours = 6
    status = "Pending"
    deadline = (Get-Date).AddDays(5).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$medTask = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $medTaskPayload

# Assign to Employee 1 and start execution
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $medTask.id
    newEmployeeId = $emp1.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$medTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($medTask.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($medTask.status -eq "In Progress") "Medium task started execution immediately (status: In Progress)"

# Manager adds a new High-priority task
$highTaskPayload = @{
    title = "HighTask_S1_$testRunId"
    description = "Urgent feature requested by client"
    requiredSkill = $skill1
    priority = "High"
    durationHours = 4
    status = "Pending"
    deadline = (Get-Date).AddDays(3).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$highTask = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $highTaskPayload

# Fetch updated Medium Task
$updatedMedTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($medTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedMedTask.status -eq "Paused") "Medium task was paused (status: $($updatedMedTask.status))"
Assert-Condition ($updatedMedTask.remainingDurationHours -gt 0) "Medium task remaining duration saved: $($updatedMedTask.remainingDurationHours)h"
Assert-Condition ($updatedMedTask.progressPercentage -ge 0) "Medium task progress percentage saved: $($updatedMedTask.progressPercentage)%"
Assert-Condition ($updatedMedTask.preemptedByTaskId -eq $highTask.id) "Medium task records preempting task ID #$($highTask.id)"
Assert-Condition ($updatedMedTask.preemptionMessage -like "*High-priority task assigned*") "Preemption message set: $($updatedMedTask.preemptionMessage)"

# Fetch updated High Task
$updatedHighTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedHighTask.status -eq "In Progress") "High-priority task is now executing (status: In Progress)"
Assert-Condition ($updatedHighTask.assignedEmployeeId -eq $emp1.id) "High-priority task assigned to Employee 1"

# Check Preemption Audit Record
$preemptions = Invoke-RestMethod -Uri "$baseUrl/scheduling/preemptions" -Method Get -Headers $authHeader
$record1 = $preemptions | Where-Object { $_.preemptedTaskId -eq $medTask.id -and $_.preemptingTaskId -eq $highTask.id }
Assert-Condition ($record1 -ne $null) "Preemption audit record logged in database"
Assert-Condition ($record1.status -eq "PAUSED") "Preemption record status is PAUSED"
Assert-Condition ($record1.employeeId -eq $emp1.id) "Preemption record links to correct Employee ID"

# Complete High-priority task
$completePayload = @{
    completionNotes = "Completed high priority task successfully"
} | ConvertTo-Json
$completedHigh = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTask.id)/complete" -Method Patch -Headers $jsonHeader -Body $completePayload
Assert-Condition ($completedHigh.status -eq "Completed") "High-priority task marked Completed"

# Verify Medium task automatically resumes from saved progress
$resumedMedTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($medTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($resumedMedTask.status -eq "In Progress") "Medium task automatically resumed (status: $($resumedMedTask.status))"
Assert-Condition ($resumedMedTask.resumedAt -ne $null) "Medium task recorded resumedAt timestamp"

# Check preemption record status updated to RESUMED
$updatedPreemptions = Invoke-RestMethod -Uri "$baseUrl/scheduling/preemptions" -Method Get -Headers $authHeader
$updatedRecord1 = $updatedPreemptions | Where-Object { $_.preemptedTaskId -eq $medTask.id -and $_.preemptingTaskId -eq $highTask.id }
Assert-Condition ($updatedRecord1.status -eq "RESUMED") "Preemption audit record status updated to RESUMED"

# ==========================================================================
# Scenario 2: Low executing -> Critical added -> Low paused -> Critical executes
# ==========================================================================
Write-Host "`n--- Scenario 2: Low Preempted by Critical ---" -ForegroundColor Yellow

$skill2 = "PreemptSkill2_$testRunId"
$emp2Payload = @{
    name = "Emp_Preempt2_$testRunId"
    email = "emp_p2_$testRunId@test.com"
    role = "DevOps"
    skills = @($skill2)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.2
} | ConvertTo-Json
$emp2 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp2Payload

# Add Low task
$lowTaskPayload = @{
    title = "LowTask_S2_$testRunId"
    description = "Code cleanup and refactoring"
    requiredSkill = $skill2
    priority = "Low"
    durationHours = 5
    status = "Pending"
    deadline = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$lowTask = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $lowTaskPayload

# Assign to Employee 2 and start execution
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $lowTask.id
    newEmployeeId = $emp2.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$lowTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($lowTask.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($lowTask.status -eq "In Progress") "Low task executing initially"

# Add Critical task
$critTaskPayload = @{
    title = "CriticalTask_S2_$testRunId"
    description = "Critical outage in production cluster"
    requiredSkill = $skill2
    priority = "Critical"
    durationHours = 3
    status = "Pending"
    deadline = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$critTask = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $critTaskPayload

# Verify Low task is paused and Critical task is executing
$updatedLow = Invoke-RestMethod -Uri "$baseUrl/tasks/$($lowTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedLow.status -eq "Paused") "Low task paused by Critical task"
Assert-Condition ($updatedLow.preemptedByTaskId -eq $critTask.id) "Low task records Critical task ID"

$updatedCrit = Invoke-RestMethod -Uri "$baseUrl/tasks/$($critTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedCrit.status -eq "In Progress") "Critical task executing immediately"

# ==========================================================================
# Scenario 3: High executing -> Medium added -> High continues
# ==========================================================================
Write-Host "`n--- Scenario 3: Lower-Priority Arrival Does NOT Interrupt Executing Task ---" -ForegroundColor Yellow

$skill3 = "PreemptSkill3_$testRunId"
$emp3Payload = @{
    name = "Emp_Preempt3_$testRunId"
    email = "emp_p3_$testRunId@test.com"
    role = "Backend"
    skills = @($skill3)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.9
} | ConvertTo-Json
$emp3 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp3Payload

# Add High task
$highTaskS3Payload = @{
    title = "HighTask_S3_$testRunId"
    description = "High priority service overhaul"
    requiredSkill = $skill3
    priority = "High"
    durationHours = 4
    status = "Pending"
    deadline = (Get-Date).AddDays(4).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$highTaskS3 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $highTaskS3Payload

# Assign to Employee 3 and start execution
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $highTaskS3.id
    newEmployeeId = $emp3.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$highTaskS3 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTaskS3.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($highTaskS3.status -eq "In Progress") "High task S3 started execution"

# Add Medium task
$medTaskS3Payload = @{
    title = "MediumTask_S3_$testRunId"
    description = "Medium priority documentation"
    requiredSkill = $skill3
    priority = "Medium"
    durationHours = 3
    status = "Pending"
    deadline = (Get-Date).AddDays(5).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$medTaskS3 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $medTaskS3Payload

# Verify High task was NOT paused
$checkHighS3 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTaskS3.id)" -Method Get -Headers $authHeader
Assert-Condition ($checkHighS3.status -eq "In Progress") "Executing High task continues uninterrupted"

# Verify Medium task remains Pending in backlog (or can be queued)
$checkMedS3 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($medTaskS3.id)" -Method Get -Headers $authHeader
Assert-Condition ($checkMedS3.status -eq "Pending") "Lower-priority Medium task remains Pending in backlog"

# ==========================================================================
# Scenario 4: High executing -> another High added -> current High continues
# ==========================================================================
Write-Host "`n--- Scenario 4: Equal-Priority Arrival Does NOT Preempt Executing Task ---" -ForegroundColor Yellow

$skill4 = "PreemptSkill4_$testRunId"
$emp4Payload = @{
    name = "Emp_Preempt4_$testRunId"
    email = "emp_p4_$testRunId@test.com"
    role = "Security"
    skills = @($skill4)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.7
} | ConvertTo-Json
$emp4 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp4Payload

# Add first High task
$highTask1S4Payload = @{
    title = "FirstHigh_S4_$testRunId"
    description = "Security audit pass 1"
    requiredSkill = $skill4
    priority = "High"
    durationHours = 5
    status = "Pending"
    deadline = (Get-Date).AddDays(3).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$highTask1S4 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $highTask1S4Payload

# Assign to Employee 4 and start execution
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $highTask1S4.id
    newEmployeeId = $emp4.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$highTask1S4 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTask1S4.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($highTask1S4.status -eq "In Progress") "First High task starts executing"

# Add second High task
$highTask2S4Payload = @{
    title = "SecondHigh_S4_$testRunId"
    description = "Security audit pass 2"
    requiredSkill = $skill4
    priority = "High"
    durationHours = 3
    status = "Pending"
    deadline = (Get-Date).AddDays(4).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$highTask2S4 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $highTask2S4Payload

# Verify first High task is STILL In Progress
$checkHigh1 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTask1S4.id)" -Method Get -Headers $authHeader
Assert-Condition ($checkHigh1.status -eq "In Progress") "First High task continues executing without interruption"

# Verify second High task remains Pending in backlog
$checkHigh2 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($highTask2S4.id)" -Method Get -Headers $authHeader
Assert-Condition ($checkHigh2.status -eq "Pending") "Second equal-priority High task remains Pending in backlog"

# ==========================================================================
# Scenario 5: Employee lacks skill for Critical -> assign to another eligible employee
# ==========================================================================
Write-Host "`n--- Scenario 5: Critical Task Assigned to Eligible Skilled Employee ---" -ForegroundColor Yellow

$javaSkill = "JavaLang_$testRunId"
$pythonSkill = "PythonLang_$testRunId"

# Employee A: has Java only
$empAPayload = @{
    name = "Emp_JavaOnly_$testRunId"
    email = "java_$testRunId@test.com"
    role = "Java Specialist"
    skills = @($javaSkill)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.5
} | ConvertTo-Json
$empA = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $empAPayload

# Employee B: has Python
$empBPayload = @{
    name = "Emp_PythonOnly_$testRunId"
    email = "python_$testRunId@test.com"
    role = "Python Specialist"
    skills = @($pythonSkill)
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.5
} | ConvertTo-Json
$empB = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $empBPayload

# Employee A is executing a Java task
$javaTaskPayload = @{
    title = "JavaTask_S5_$testRunId"
    description = "Java backend development"
    requiredSkill = $javaSkill
    priority = "Medium"
    durationHours = 4
    status = "Pending"
    deadline = (Get-Date).AddDays(5).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$javaTask = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $javaTaskPayload

Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $javaTask.id
    newEmployeeId = $empA.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$javaTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($javaTask.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($javaTask.status -eq "In Progress") "Java task executing on Employee A"

# Employee B is executing a Medium Python task
$prepPython = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body (@{
    title = "PrepPython_S5_$testRunId"
    description = "Python task"
    requiredSkill = $pythonSkill
    priority = "Medium"
    durationHours = 3
    deadline = (Get-Date).AddDays(4).ToString("yyyy-MM-dd")
} | ConvertTo-Json)

Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $prepPython.id
    newEmployeeId = $empB.id
    reason = "Initial assignment"
} | ConvertTo-Json) | Out-Null
$prepPython = Invoke-RestMethod -Uri "$baseUrl/tasks/$($prepPython.id)/start" -Method Patch -Headers $authHeader
Assert-Condition ($prepPython.status -eq "In Progress") "Prep Python task executing on Employee B"

# Critical task arrives requiring Python
$critPythonPayload = @{
    title = "CriticalPython_S5_$testRunId"
    description = "Critical ML model hotfix"
    requiredSkill = $pythonSkill
    priority = "Critical"
    durationHours = 3
    status = "Pending"
    deadline = (Get-Date).AddDays(2).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$critPython = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $critPythonPayload

# Assert Critical task assigned to Employee B (who has Python), NOT Employee A
$updatedCritPython = Invoke-RestMethod -Uri "$baseUrl/tasks/$($critPython.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedCritPython.assignedEmployeeId -eq $empB.id) "Critical task assigned to skilled Employee B (#$($empB.id))"
Assert-Condition ($updatedCritPython.assignedEmployeeId -ne $empA.id) "Employee A was correctly NOT assigned Python task"
Assert-Condition ($updatedCritPython.status -eq "In Progress") "Critical task executing on Employee B"

# Verify Employee B's previous Medium task was paused
$updatedPrepPython = Invoke-RestMethod -Uri "$baseUrl/tasks/$($prepPython.id)" -Method Get -Headers $authHeader
Assert-Condition ($updatedPrepPython.status -eq "Paused") "Employee B's Medium task was paused"

# Verify Employee A's Java task was NOT preempted
$checkJavaTask = Invoke-RestMethod -Uri "$baseUrl/tasks/$($javaTask.id)" -Method Get -Headers $authHeader
Assert-Condition ($checkJavaTask.status -eq "In Progress") "Employee A continues executing Java task uninterrupted"

# ==========================================================================
# Scenario 6: Higher-priority task added but no employee eligible -> retain Unassigned & notify manager
# ==========================================================================
Write-Host "`n--- Scenario 6: Higher-Priority Task with No Eligible Employee ---" -ForegroundColor Yellow

$impossibleSkill = "QuantumHaskell_$testRunId"
$critImpossiblePayload = @{
    title = "CriticalQuantum_S6_$testRunId"
    description = "Requires non-existent skill"
    requiredSkill = $impossibleSkill
    priority = "Critical"
    durationHours = 5
    status = "Pending"
    deadline = (Get-Date).AddDays(2).ToString("yyyy-MM-dd")
} | ConvertTo-Json
$critImpossible = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $critImpossiblePayload

# Assert task remains Unassigned
Assert-Condition ($critImpossible.status -eq "Unassigned") "Task status is Unassigned (status: $($critImpossible.status))"
Assert-Condition ($critImpossible.assignedEmployeeId -eq $null) "Task has no assigned employee"

# Check that manager notification was created
$notifications = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get -Headers $authHeader
$unassignedNotif = $notifications | Where-Object { ($_.relatedTaskId -eq $critImpossible.id -or $_.message -like "*CriticalQuantum_S6_$testRunId*") -and $_.type -eq "ALERT" }
Assert-Condition ($unassignedNotif -ne $null) "Manager notification generated for unassigned Critical task"
Assert-Condition ($unassignedNotif.message -like "*Critical task*") "Notification explains Critical task unassigned: $($unassignedNotif.message)"

# ==========================================================================
# Status Endpoint Verification: GET /api/scheduling/status
# ==========================================================================
Write-Host "`n--- Status Endpoint Verification: GET /api/scheduling/status ---" -ForegroundColor Yellow

$statusRes = Invoke-RestMethod -Uri "$baseUrl/scheduling/status" -Method Get -Headers $authHeader
Assert-Condition ($statusRes.executingTasks -ne $null) "status endpoint returns executingTasks"
Assert-Condition ($statusRes.pausedTasks -ne $null) "status endpoint returns pausedTasks"
Assert-Condition ($statusRes.assignedQueue -ne $null) "status endpoint returns assignedQueue"
Assert-Condition ($statusRes.preemptionHistory -ne $null) "status endpoint returns preemptionHistory"
Assert-Condition ($statusRes.priorityDistribution -ne $null) "status endpoint returns priorityDistribution"
Assert-Condition ($statusRes.totalPreemptions -ge 2) "Total recorded preemptions is at least 2: $($statusRes.totalPreemptions)"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "ALL PREEMPTIVE SCHEDULING SCENARIOS PASSED WITH ZERO ERRORS!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
