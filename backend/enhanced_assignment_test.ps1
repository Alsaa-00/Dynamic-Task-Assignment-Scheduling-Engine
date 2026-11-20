# ==============================================================================
# ENHANCED DECISION-MAKING & SCENARIOS TEST SUITE - TASKFLOW ENGINE
# Tests:
# 1. Equal Workload Tie-Breaking: Existing Commitments & Deadlines
# 2. Equal Workload Tie-Breaking: Employee Priority
# 3. Equal Workload Tie-Breaking: Employee Performance
# 4. Equal Workload Tie-Breaking: Lowest Employee ID Final Tie-Breaker
# 5. No Suitable Employee: Missing Required Skills
# 6. No Suitable Employee: Employee Unavailability
# 7. No Suitable Employee: Maximum Workload Capacity Reached
# 8. No Suitable Employee: Insufficient Remaining Capacity
# 9. Immediate Workload Calculation Update
# ==============================================================================

$baseUrl = "http://localhost:8080/api"
$passed = 0
$failed = 0

function Assert-Condition($condition, $message) {
    if ($condition) {
        Write-Host " [PASS] $message" -ForegroundColor Green
        $script:passed++
    } else {
        Write-Host " [FAIL] $message" -ForegroundColor Red
        $script:failed++
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " RUNNING ENHANCED GREEDY HEURISTIC & SCENARIO TEST SUITE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Cleanup list to delete temporary entities after test
$createdEmpIds = @()
$createdTaskIds = @()

try {
    # --------------------------------------------------------------------------
    # SCENARIO 1: Tie-Breaking by Existing Task Commitments & Deadlines
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 1: Commitments & Deadlines Tie-Breaking ---" -ForegroundColor Yellow
    # Emp A: DevOps, 0 workload initially
    $empA = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "DevOps Alice"
        email = "alice_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "DevOps Engineer"
        skills = @("DevOpsTest")
        maxWorkload = 40
        priority = 1
        performance = 5.0
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empA.id

    # Emp B: DevOps, 0 workload initially
    $empB = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "DevOps Bob"
        email = "bob_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "DevOps Engineer"
        skills = @("DevOpsTest")
        maxWorkload = 40
        priority = 1
        performance = 5.0
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empB.id

    # Give Bob 2 small tasks of 2h each (total 4h)
    $taskB1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Bob Task 1"
        requiredSkill = "DevOpsTest"
        durationHours = 2
        priority = "Low"
        deadline = "2026-12-01"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskB1.id
    Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body (@{
        taskId = $taskB1.id
        newEmployeeId = $empB.id
        reason = "Initial load"
    } | ConvertTo-Json) -ContentType "application/json" | Out-Null

    $taskB2 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Bob Task 2"
        requiredSkill = "DevOpsTest"
        durationHours = 2
        priority = "Low"
        deadline = "2026-12-02"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskB2.id
    Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body (@{
        taskId = $taskB2.id
        newEmployeeId = $empB.id
        reason = "Initial load"
    } | ConvertTo-Json) -ContentType "application/json" | Out-Null

    # Give Alice 1 task of 4h (total 4h, so Alice and Bob both have equal 4h workload!)
    $taskA1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Alice Task 1"
        requiredSkill = "DevOpsTest"
        durationHours = 4
        priority = "Low"
        deadline = "2026-12-10"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskA1.id
    Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body (@{
        taskId = $taskA1.id
        newEmployeeId = $empA.id
        reason = "Initial load"
    } | ConvertTo-Json) -ContentType "application/json" | Out-Null

    # Now create candidate task (duration 2h). Alice has 1 commitment, Bob has 2 commitments!
    $testTask1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Deploy ArgoCD Cluster"
        requiredSkill = "DevOpsTest"
        durationHours = 2
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $testTask1.id

    # Run assignment
    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $assignedTask1 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($testTask1.id)" -Method Get
    Assert-Condition ($assignedTask1.assignedEmployeeId -eq $empA.id) "Tie-Breaking: Alice chosen over Bob because Alice has fewer active commitments (1 vs 2)"

    # --------------------------------------------------------------------------
    # SCENARIO 2: Tie-Breaking by Employee Priority
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 2: Employee Priority Tie-Breaking ---" -ForegroundColor Yellow
    # Emp C: Priority 3 (Senior Lead)
    $empC = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Senior Lead Charlie"
        email = "charlie_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Lead ML Engineer"
        skills = @("MLPriorityTest")
        maxWorkload = 40
        priority = 3
        performance = 4.5
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empC.id

    # Emp D: Priority 1 (Junior Engineer)
    $empD = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Junior Dan"
        email = "dan_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Junior ML Engineer"
        skills = @("MLPriorityTest")
        maxWorkload = 40
        priority = 1
        performance = 4.5
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empD.id

    $testTask2 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Fine-tune Transformer Embeddings"
        requiredSkill = "MLPriorityTest"
        durationHours = 5
        priority = "Critical"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $testTask2.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $assignedTask2 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($testTask2.id)" -Method Get
    Assert-Condition ($assignedTask2.assignedEmployeeId -eq $empC.id) "Tie-Breaking: Charlie chosen over Dan due to higher Employee Priority (3 vs 1)"

    # --------------------------------------------------------------------------
    # SCENARIO 3: Tie-Breaking by Relevant Performance
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 3: Relevant Performance Tie-Breaking ---" -ForegroundColor Yellow
    # Emp E: Performance 4.9
    $empE = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "High Perf Emma"
        email = "emma_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Data Scientist"
        skills = @("DataPerfTest")
        maxWorkload = 40
        priority = 1
        performance = 4.9
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empE.id

    # Emp F: Performance 4.1
    $empF = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Standard Frank"
        email = "frank_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Data Scientist"
        skills = @("DataPerfTest")
        maxWorkload = 40
        priority = 1
        performance = 4.1
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empF.id

    $testTask3 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Train Churn Prediction Model"
        requiredSkill = "DataPerfTest"
        durationHours = 4
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $testTask3.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $assignedTask3 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($testTask3.id)" -Method Get
    Assert-Condition ($assignedTask3.assignedEmployeeId -eq $empE.id) "Tie-Breaking: Emma chosen over Frank due to higher Performance Rating (4.9 vs 4.1)"

    # --------------------------------------------------------------------------
    # SCENARIO 4: Final Tie-Breaker - Lowest Employee ID
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 4: Lowest Employee ID Final Tie-Breaker ---" -ForegroundColor Yellow
    $empG = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "First Grace"
        email = "grace_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Security Analyst"
        skills = @("IDTieTest")
        maxWorkload = 40
        priority = 1
        performance = 5.0
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empG.id

    $empH = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Second Henry"
        email = "henry_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Security Analyst"
        skills = @("IDTieTest")
        maxWorkload = 40
        priority = 1
        performance = 5.0
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empH.id

    $testTask4 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Verify SOC2 Compliance Controls"
        requiredSkill = "IDTieTest"
        durationHours = 3
        priority = "Medium"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $testTask4.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $assignedTask4 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($testTask4.id)" -Method Get
    $expectedId = [Math]::Min($empG.id, $empH.id)
    Assert-Condition ($assignedTask4.assignedEmployeeId -eq $expectedId) "Tie-Breaking: Employee with lower ID ($expectedId) chosen when all other criteria are identical"

    # --------------------------------------------------------------------------
    # SCENARIO 5: No Suitable Employee - Missing Required Skills
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 5: Missing Required Skills ---" -ForegroundColor Yellow
    $taskMissingSkill = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Develop Quantum Cryptography Protocol"
        requiredSkill = "QuantumComputingXYZ"
        durationHours = 6
        priority = "Critical"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskMissingSkill.id

    $runResults5 = Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post
    $unassignedTask5 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($taskMissingSkill.id)" -Method Get
    Assert-Condition ($unassignedTask5.status -eq "Unassigned" -and $unassignedTask5.assignedEmployeeId -eq $null) "Missing Skill: Task status kept as UNASSIGNED with no assignee"

    # Check Assignment history failure reason
    $history5 = Invoke-RestMethod -Uri "$baseUrl/assignments/history" -Method Get
    $record5 = $history5 | Where-Object { $_.taskId -eq $taskMissingSkill.id -and $_.outcome -eq "FAILED" } | Select-Object -First 1
    Assert-Condition ($record5.reason -like "*Missing required skills*") "Missing Skill: Audit log records 'Missing required skills' reason"

    # Check Manager notification
    $notifs5 = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get
    $notif5 = $notifs5 | Where-Object { $_.relatedTaskId -eq $taskMissingSkill.id -and $_.message -like "*Missing required skills*" } | Select-Object -First 1
    Assert-Condition ($notif5 -ne $null) "Missing Skill: Manager notification generated explaining 'Missing required skills'"

    # --------------------------------------------------------------------------
    # SCENARIO 6: No Suitable Employee - Employee Unavailability
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 6: Employee Unavailability ---" -ForegroundColor Yellow
    $empUnavail = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Unavailable Ian"
        email = "ian_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Blockchain Architect"
        skills = @("SolidityTest")
        maxWorkload = 40
        priority = 1
        performance = 5.0
        available = $false # UNAVAILABLE!
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empUnavail.id

    $taskUnavail = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Deploy Smart Contract on Testnet"
        requiredSkill = "SolidityTest"
        durationHours = 4
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskUnavail.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $unassignedTask6 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($taskUnavail.id)" -Method Get
    Assert-Condition ($unassignedTask6.status -eq "Unassigned") "Employee Unavailability: Task kept UNASSIGNED when skilled employee is unavailable"

    $history6 = Invoke-RestMethod -Uri "$baseUrl/assignments/history" -Method Get
    $record6 = $history6 | Where-Object { $_.taskId -eq $taskUnavail.id -and $_.outcome -eq "FAILED" } | Select-Object -First 1
    Assert-Condition ($record6.reason -like "*Employee unavailability*") "Employee Unavailability: Audit log records 'Employee unavailability' reason"

    $notifs6 = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get
    $notif6 = $notifs6 | Where-Object { $_.relatedTaskId -eq $taskUnavail.id -and $_.message -like "*Employee unavailability*" } | Select-Object -First 1
    Assert-Condition ($notif6 -ne $null) "Employee Unavailability: Manager notification generated explaining 'Employee unavailability'"

    # --------------------------------------------------------------------------
    # SCENARIO 7: No Suitable Employee - Maximum Workload Capacity Reached
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 7: Maximum Workload Capacity Reached ---" -ForegroundColor Yellow
    $empFull = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Full Capacity Jack"
        email = "jack_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Firmware Engineer"
        skills = @("FirmwareTest")
        maxWorkload = 10
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empFull.id

    # Give Jack a 10h task so he is exactly at maxWorkload (10/10)
    $taskFill = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Write SPI Bus Driver"
        requiredSkill = "FirmwareTest"
        durationHours = 10
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskFill.id
    Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body (@{
        taskId = $taskFill.id
        newEmployeeId = $empFull.id
        reason = "Fill capacity"
    } | ConvertTo-Json) -ContentType "application/json" | Out-Null

    # Now create new task requiring FirmwareTest (duration 2h)
    $taskOverMax = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Debug DMA Buffer Overrun"
        requiredSkill = "FirmwareTest"
        durationHours = 2
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskOverMax.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $unassignedTask7 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($taskOverMax.id)" -Method Get
    Assert-Condition ($unassignedTask7.status -eq "Unassigned") "Max Capacity: Task kept UNASSIGNED when employee is already at capacity"

    $history7 = Invoke-RestMethod -Uri "$baseUrl/assignments/history" -Method Get
    $record7 = $history7 | Where-Object { $_.taskId -eq $taskOverMax.id -and $_.outcome -eq "FAILED" } | Select-Object -First 1
    Assert-Condition ($record7.reason -like "*Maximum workload capacity reached*") "Max Capacity: Audit log records 'Maximum workload capacity reached' reason"

    $notifs7 = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get
    $notif7 = $notifs7 | Where-Object { $_.relatedTaskId -eq $taskOverMax.id -and $_.message -like "*Maximum workload capacity reached*" } | Select-Object -First 1
    Assert-Condition ($notif7 -ne $null) "Max Capacity: Manager notification generated explaining 'Maximum workload capacity reached'"

    # --------------------------------------------------------------------------
    # SCENARIO 8: No Suitable Employee - Insufficient Remaining Capacity
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 8: Insufficient Remaining Capacity ---" -ForegroundColor Yellow
    $empPartial = Invoke-RestMethod -Uri "$baseUrl/employees" -Method Post -Body (@{
        name = "Partial Karen"
        email = "karen_$([System.Guid]::NewGuid().ToString().Substring(0,8))@test.com"
        role = "Embedded Engineer"
        skills = @("EmbeddedTest")
        maxWorkload = 20
        available = $true
    } | ConvertTo-Json) -ContentType "application/json"
    $createdEmpIds += $empPartial.id

    # Give Karen 15h of tasks (remaining capacity = 5h)
    $taskPart1 = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "Bootloader Implementation"
        requiredSkill = "EmbeddedTest"
        durationHours = 15
        priority = "Low"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskPart1.id
    Invoke-RestMethod -Uri "$baseUrl/assignments/reassign" -Method Post -Body (@{
        taskId = $taskPart1.id
        newEmployeeId = $empPartial.id
        reason = "Initial partial load"
    } | ConvertTo-Json) -ContentType "application/json" | Out-Null

    # Now create task needing 8h (15 + 8 = 23 > 20, exceeding remaining 5h)
    $taskExceedRem = Invoke-RestMethod -Uri "$baseUrl/tasks" -Method Post -Body (@{
        title = "CAN Bus Protocol Stack"
        requiredSkill = "EmbeddedTest"
        durationHours = 8
        priority = "High"
    } | ConvertTo-Json) -ContentType "application/json"
    $createdTaskIds += $taskExceedRem.id

    Invoke-RestMethod -Uri "$baseUrl/assignments/run" -Method Post | Out-Null
    $unassignedTask8 = Invoke-RestMethod -Uri "$baseUrl/tasks/$($taskExceedRem.id)" -Method Get
    Assert-Condition ($unassignedTask8.status -eq "Unassigned") "Insufficient Remaining: Task kept UNASSIGNED when task duration exceeds remaining capacity"

    $history8 = Invoke-RestMethod -Uri "$baseUrl/assignments/history" -Method Get
    $record8 = $history8 | Where-Object { $_.taskId -eq $taskExceedRem.id -and $_.outcome -eq "FAILED" } | Select-Object -First 1
    Assert-Condition ($record8.reason -like "*Insufficient remaining capacity*") "Insufficient Remaining: Audit log records 'Insufficient remaining capacity' reason"

    $notifs8 = Invoke-RestMethod -Uri "$baseUrl/notifications" -Method Get
    $notif8 = $notifs8 | Where-Object { $_.relatedTaskId -eq $taskExceedRem.id -and $_.message -like "*Insufficient remaining capacity*" } | Select-Object -First 1
    Assert-Condition ($notif8 -ne $null) "Insufficient Remaining: Manager notification generated explaining 'Insufficient remaining capacity'"

    # --------------------------------------------------------------------------
    # SCENARIO 9: Immediate Workload Calculation Update
    # --------------------------------------------------------------------------
    Write-Host "`n--- Testing Scenario 9: Immediate Workload Update ---" -ForegroundColor Yellow
    # Check that Alice's workload increased immediately after assigning testTask1 (4h + 2h = 6h)
    $refreshedAlice = Invoke-RestMethod -Uri "$baseUrl/employees/$($empA.id)" -Method Get
    Assert-Condition ($refreshedAlice.workloadHours -eq 6) "Immediate Workload Update: Employee workload dynamically updated to 6h immediately upon assignment"

} catch {
    Write-Host "Test execution exception: $_" -ForegroundColor Red
    $script:failed++
} finally {
    Write-Host "`n--- Cleaning Up Temporary Test Entities ---" -ForegroundColor Cyan
    foreach ($tId in $createdTaskIds) {
        try {
            Invoke-RestMethod -Uri "$baseUrl/tasks/$tId/complete" -Method Patch -Body (@{ notes = "Test cleanup" } | ConvertTo-Json) -ContentType "application/json" -ErrorAction SilentlyContinue | Out-Null
            Invoke-RestMethod -Uri "$baseUrl/tasks/$tId" -Method Delete -ErrorAction SilentlyContinue | Out-Null
        } catch {}
    }
    foreach ($eId in $createdEmpIds) {
        try {
            Invoke-RestMethod -Uri "$baseUrl/employees/$eId" -Method Delete -ErrorAction SilentlyContinue | Out-Null
        } catch {}
    }
    Write-Host "Cleanup completed." -ForegroundColor Cyan
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host " SUMMARY: $passed PASSED, $failed FAILED" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "==========================================================" -ForegroundColor Cyan
