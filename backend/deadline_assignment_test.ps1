# Deadline & Constraint Assignment Automated Test Suite
# Tests all requirements from prompt:
# 1. Required Skill Matching
# 2. Employee Availability
# 3. Workload Capacity
# 4. Deadline Feasibility (expired & working time)
# 5. Greedy Selection with different workloads
# 6. Tie-breaking with equal workloads (deterministic employee ID)
# 7. No suitable employee scenario & manager notification
# 8. Complete Assignment Explanation (before/after workload, reason, candidates)

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
Write-Host "STARTING COMPREHENSIVE CONSTRAINT & DEADLINE TEST SUITE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Clean up / reset test state or generate unique items
$testRunId = [guid]::NewGuid().ToString().Substring(0, 8)
Write-Host "Test Run ID: $testRunId"

# --------------------------------------------------------------------------
# Test 1: Employee available but lacks required skill
# --------------------------------------------------------------------------
Write-Host "`n--- Test 1: Skill Mismatch Rejection ---" -ForegroundColor Yellow
$test1Skill = "SpecialSkill_$testRunId"
$emp1Payload = @{
    name = "SkillMismatchEmp_$testRunId"
    email = "skill_$testRunId@test.com"
    role = "Developer"
    skills = @("Kotlin_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.5
} | ConvertTo-Json
$emp1 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp1Payload

$task1Payload = @{
    title = "SkillTestTask_$testRunId"
    description = "Requires SpecialSkill"
    requiredSkill = $test1Skill
    priority = "Medium"
    durationHours = 5
    status = "Pending"
} | ConvertTo-Json
$task1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task1Payload

$res1 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t1Result = $res1 | Where-Object { $_.taskId -eq $task1.id }
Assert-Condition ($t1Result -ne $null) "Task 1 exists in assignment results"
Assert-Condition ($t1Result.status -eq "Unassigned") "Task 1 is Unassigned due to skill mismatch"
Assert-Condition ($t1Result.reason -like "*No employee with the required skill*") "Task 1 reason diagnoses skill absence: $($t1Result.reason)"

# Check rejected candidate entry
$t1Emp1Rej = $t1Result.rejectedCandidates | Where-Object { $_.employeeId -eq $emp1.id }
Assert-Condition ($t1Emp1Rej -ne $null) "Employee 1 appears in rejected candidates list"
Assert-Condition ($t1Emp1Rej.rejectionCategory -eq "SKILL_MISMATCH") "Employee 1 rejection category is SKILL_MISMATCH"

# --------------------------------------------------------------------------
# Test 2: Employee has required skill but is unavailable
# --------------------------------------------------------------------------
Write-Host "`n--- Test 2: Employee Unavailable Rejection ---" -ForegroundColor Yellow
$emp2Payload = @{
    name = "UnavailableEmp_$testRunId"
    email = "unavail_$testRunId@test.com"
    role = "Developer"
    skills = @("RustLang_$testRunId")
    available = $false
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.0
} | ConvertTo-Json
$emp2 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp2Payload

$task2Payload = @{
    title = "RustTask_$testRunId"
    description = "Requires Rust"
    requiredSkill = "RustLang_$testRunId"
    priority = "High"
    durationHours = 6
    status = "Pending"
} | ConvertTo-Json
$task2 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task2Payload

$res2 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t2Result = $res2 | Where-Object { $_.taskId -eq $task2.id }
Assert-Condition ($t2Result -ne $null) "Task 2 exists in assignment results"
Assert-Condition ($t2Result.status -eq "Unassigned") "Task 2 is Unassigned due to unavailability"
Assert-Condition ($t2Result.reason -like "*Employee unavailable*") "Task 2 reason diagnoses unavailability: $($t2Result.reason)"

$t2Emp2Rej = $t2Result.rejectedCandidates | Where-Object { $_.employeeId -eq $emp2.id }
Assert-Condition ($t2Emp2Rej -ne $null) "Employee 2 appears in rejected candidates"
Assert-Condition ($t2Emp2Rej.rejectionCategory -eq "UNAVAILABLE") "Employee 2 rejection category is UNAVAILABLE"

# --------------------------------------------------------------------------
# Test 3: Employee has required skill but insufficient workload capacity
# --------------------------------------------------------------------------
Write-Host "`n--- Test 3: Insufficient Workload Capacity Rejection ---" -ForegroundColor Yellow
$emp3Payload = @{
    name = "CapacityEmp_$testRunId"
    email = "capacity_$testRunId@test.com"
    role = "Developer"
    skills = @("GoLang_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 8
    priority = 1
    performance = 4.2
} | ConvertTo-Json
$emp3 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp3Payload

# Now create Task 3 that requires 10h (emp3 maxWorkload is only 8h)
$task3Payload = @{
    title = "GoTask_$testRunId"
    description = "Requires 10h with max 8h"
    requiredSkill = "GoLang_$testRunId"
    priority = "High"
    durationHours = 10
    status = "Pending"
} | ConvertTo-Json
$task3 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task3Payload

$res3 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t3Result = $res3 | Where-Object { $_.taskId -eq $task3.id }
Assert-Condition ($t3Result -ne $null) "Task 3 exists in assignment results"
Assert-Condition ($t3Result.status -eq "Unassigned") "Task 3 is Unassigned due to capacity exceeded"
Assert-Condition ($t3Result.reason -like "*Insufficient workload capacity*") "Task 3 diagnoses insufficient capacity: $($t3Result.reason)"

$t3Emp3Rej = $t3Result.rejectedCandidates | Where-Object { $_.employeeId -eq $emp3.id }
Assert-Condition ($t3Emp3Rej -ne $null) "Employee 3 appears in rejected candidates"
Assert-Condition ($t3Emp3Rej.rejectionCategory -eq "CAPACITY_EXCEEDED") "Employee 3 rejection category is CAPACITY_EXCEEDED"

# --------------------------------------------------------------------------
# Test 4: Deadline Feasibility (Expired Deadline & Working Hours Calculation)
# --------------------------------------------------------------------------
Write-Host "`n--- Test 4a: Expired Deadline Rejection ---" -ForegroundColor Yellow
$emp4Payload = @{
    name = "DeadlineEmp_$testRunId"
    email = "deadline_$testRunId@test.com"
    role = "Specialist"
    skills = @("Security_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.8
} | ConvertTo-Json
$emp4 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp4Payload

$yesterday = (Get-Date).AddDays(-2).ToString("yyyy-MM-dd")
$task4aPayload = @{
    title = "ExpiredTask_$testRunId"
    description = "Deadline already passed"
    requiredSkill = "Security_$testRunId"
    priority = "Critical"
    durationHours = 4
    deadline = $yesterday
    status = "Pending"
} | ConvertTo-Json
$task4a = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task4aPayload

$res4a = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t4aResult = $res4a | Where-Object { $_.taskId -eq $task4a.id }
Assert-Condition ($t4aResult -ne $null) "Task 4a exists in assignment results"
Assert-Condition ($t4aResult.status -eq "Unassigned") "Task 4a with expired deadline is Unassigned"
Assert-Condition ($t4aResult.deadlineStatus -eq "EXPIRED") "Task 4a deadlineStatus is EXPIRED"
Assert-Condition ($t4aResult.reason -like "*Deadline cannot be met*") "Task 4a diagnoses deadline expired: $($t4aResult.reason)"

Write-Host "`n--- Test 4b: Working Hours Infeasible Rejection ---" -ForegroundColor Yellow
# Deadline is tomorrow (1 day remaining = 8h working time)
# Task requires 16h (exceeds available working hours)
$tomorrow = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
$task4bPayload = @{
    title = "InfeasibleWorkingHoursTask_$testRunId"
    description = "Requires 16h with only 8h before deadline"
    requiredSkill = "Security_$testRunId"
    priority = "Critical"
    durationHours = 16
    deadline = $tomorrow
    status = "Pending"
} | ConvertTo-Json
$task4b = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task4bPayload

$res4b = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t4bResult = $res4b | Where-Object { $_.taskId -eq $task4b.id }
Assert-Condition ($t4bResult -ne $null) "Task 4b exists in assignment results"
Assert-Condition ($t4bResult.status -eq "Unassigned") "Task 4b is Unassigned due to infeasible deadline"
Assert-Condition ($t4bResult.deadlineStatus -eq "INFEASIBLE") "Task 4b deadlineStatus is INFEASIBLE"
Assert-Condition ($t4bResult.reason -like "*Deadline cannot be met*") "Task 4b diagnoses insufficient working hours: $($t4bResult.reason)"

# --------------------------------------------------------------------------
# Test 5: Multiple Eligible Candidates with Different Workloads (Greedy Selection)
# --------------------------------------------------------------------------
Write-Host "`n--- Test 5: Greedy Selection (Lowest Workload) ---" -ForegroundColor Yellow
$emp5aPayload = @{
    name = "HeavyEmp_$testRunId"
    email = "heavy_$testRunId@test.com"
    role = "Developer"
    skills = @("React_$testRunId")
    available = $true
    workloadHours = 15
    maxWorkload = 40
    priority = 1
    performance = 4.0
} | ConvertTo-Json
$emp5a = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp5aPayload

# Pre-assign task to heavy emp using /assignments/reassign
$createdPrepTask5a = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body (@{
    title = "HeavyPrep_$testRunId"
    requiredSkill = "React_$testRunId"
    priority = "Low"
    durationHours = 15
} | ConvertTo-Json)
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $createdPrepTask5a.id
    newEmployeeId = $emp5a.id
    reason = "Initial load"
} | ConvertTo-Json) | Out-Null

$emp5bPayload = @{
    name = "LightEmp_$testRunId"
    email = "light_$testRunId@test.com"
    role = "Developer"
    skills = @("React_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 1
    performance = 4.0
} | ConvertTo-Json
$emp5b = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp5bPayload

# Pre-assign task to light emp using /assignments/reassign
$createdPrepTask5b = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body (@{
    title = "LightPrep_$testRunId"
    requiredSkill = "React_$testRunId"
    priority = "Low"
    durationHours = 2
} | ConvertTo-Json)
Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Headers $jsonHeader -Body (@{
    taskId = $createdPrepTask5b.id
    newEmployeeId = $emp5b.id
    reason = "Initial load"
} | ConvertTo-Json) | Out-Null

$task5Payload = @{
    title = "ReactTask_$testRunId"
    description = "Greedy workload selection test"
    requiredSkill = "React_$testRunId"
    priority = "High"
    durationHours = 4
    deadline = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
    status = "Pending"
} | ConvertTo-Json
$task5 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task5Payload

$res5 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t5Result = $res5 | Where-Object { $_.taskId -eq $task5.id }
Assert-Condition ($t5Result -ne $null) "Task 5 exists in assignment results"
Assert-Condition ($t5Result.status -eq "Assigned") "Task 5 successfully Assigned"
Assert-Condition ($t5Result.employeeId -eq $emp5b.id) "Task 5 assigned to LightEmp (workload 2h < 15h)"
Assert-Condition ($t5Result.workloadBefore -eq 2) "Workload before recorded correctly as 2h"
Assert-Condition ($t5Result.workloadAfter -eq 6) "Workload after recorded correctly as 6h"
Assert-Condition ($t5Result.eligibleCandidates.Count -ge 2) "Both employees recorded as eligible candidates"

# --------------------------------------------------------------------------
# Test 6: Multiple Eligible Candidates with Equal Workloads (Deterministic Tie-breaking)
# --------------------------------------------------------------------------
Write-Host "`n--- Test 6: Equal Workloads Deterministic Tie-breaking ---" -ForegroundColor Yellow
$emp6aPayload = @{
    name = "TieEmpA_$testRunId"
    email = "tieA_$testRunId@test.com"
    role = "DevOps"
    skills = @("Kubernetes_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.5
} | ConvertTo-Json
$emp6a = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp6aPayload

$emp6bPayload = @{
    name = "TieEmpB_$testRunId"
    email = "tieB_$testRunId@test.com"
    role = "DevOps"
    skills = @("Kubernetes_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 40
    priority = 2
    performance = 4.5
} | ConvertTo-Json
$emp6b = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp6bPayload

$task6Payload = @{
    title = "K8sTask_$testRunId"
    description = "Equal workloads tie-breaking test"
    requiredSkill = "Kubernetes_$testRunId"
    priority = "High"
    durationHours = 5
    deadline = (Get-Date).AddDays(5).ToString("yyyy-MM-dd")
    status = "Pending"
} | ConvertTo-Json
$task6 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task6Payload

$res6 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t6Result = $res6 | Where-Object { $_.taskId -eq $task6.id }
Assert-Condition ($t6Result -ne $null) "Task 6 exists in assignment results"
Assert-Condition ($t6Result.status -eq "Assigned") "Task 6 is Assigned"

$expectedWinnerId = [Math]::Min($emp6a.id, $emp6b.id)
Assert-Condition ($t6Result.employeeId -eq $expectedWinnerId) "Tie broken deterministically by lowest Employee ID (#$expectedWinnerId)"

# --------------------------------------------------------------------------
# Test 7: No Suitable Employee & Manager Notification Generation
# --------------------------------------------------------------------------
Write-Host "`n--- Test 7: No Suitable Employee & Manager Alert ---" -ForegroundColor Yellow
$task7Payload = @{
    title = "QuantumTask_$testRunId"
    description = "Unattainable skill"
    requiredSkill = "QuantumComputing_$testRunId"
    priority = "Critical"
    durationHours = 8
    status = "Pending"
} | ConvertTo-Json
$task7 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body $task7Payload

$res7 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t7Result = $res7 | Where-Object { $_.taskId -eq $task7.id }
Assert-Condition ($t7Result -ne $null) "Task 7 exists in assignment results"
Assert-Condition ($t7Result.status -eq "Unassigned") "Task 7 is Unassigned"
Assert-Condition ($t7Result.reason -like "*No employee with the required skill*") "Task 7 reason is exact: $($t7Result.reason)"

# Check Manager Notifications
$notifs = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get -Headers $authHeader
$unassignedAlert = $notifs | Where-Object { $_.type -eq "ALERT" -and $_.message -like "*QuantumTask_$testRunId*" }
Assert-Condition ($unassignedAlert -ne $null) "Manager received unassigned task alert notification"
Assert-Condition ($unassignedAlert.message -like "*No employee with the required skill*") "Notification explains exact failure reason"

# --------------------------------------------------------------------------
# Test 8: Immediate Sequential Workload Update Between Tasks
# --------------------------------------------------------------------------
Write-Host "`n--- Test 8: Immediate Sequential Workload Update ---" -ForegroundColor Yellow
$emp8Payload = @{
    name = "SoloEmp_$testRunId"
    email = "solo_$testRunId@test.com"
    role = "Dev"
    skills = @("Flutter_$testRunId")
    available = $true
    workloadHours = 0
    maxWorkload = 10
    priority = 1
    performance = 4.0
} | ConvertTo-Json
$emp8 = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Headers $jsonHeader -Body $emp8Payload

# Task 8a takes 6 hours (leaves 4 hours capacity)
$task8a = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body (@{
    title = "FlutterTask1_$testRunId"
    requiredSkill = "Flutter_$testRunId"
    priority = "Critical"
    durationHours = 6
    status = "Pending"
} | ConvertTo-Json)

# Task 8b takes 6 hours (needs 6 hours, but only 4 hours remain after Task 8a!)
$task8b = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Headers $jsonHeader -Body (@{
    title = "FlutterTask2_$testRunId"
    requiredSkill = "Flutter_$testRunId"
    priority = "High"
    durationHours = 6
    status = "Pending"
} | ConvertTo-Json)

$res8 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post -Headers $jsonHeader
$t8aResult = $res8 | Where-Object { $_.taskId -eq $task8a.id }
$t8bResult = $res8 | Where-Object { $_.taskId -eq $task8b.id }

Assert-Condition ($t8aResult.status -eq "Assigned") "First task (8a) assigned to SoloEmp"
Assert-Condition ($t8aResult.workloadBefore -eq 0 -and $t8aResult.workloadAfter -eq 6) "Workload updated from 0 to 6h"
Assert-Condition ($t8bResult.status -eq "Unassigned") "Second task (8b) rejected because workload was immediately updated to 6h (insufficient capacity)"
Assert-Condition ($t8bResult.reason -like "*Insufficient workload capacity*") "Second task correctly diagnosed with Insufficient workload capacity"

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "ALL 24 CONSTRAINT & DEADLINE TESTS PASSED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
