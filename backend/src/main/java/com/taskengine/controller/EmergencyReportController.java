package com.taskengine.controller;

import com.taskengine.model.EmergencyReport;
import com.taskengine.service.EmergencyReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/emergencies")
@CrossOrigin(origins = "*")
public class EmergencyReportController {

    private final EmergencyReportService emergencyReportService;

    public EmergencyReportController(EmergencyReportService emergencyReportService) {
        this.emergencyReportService = emergencyReportService;
    }

    /** Submit a new emergency (employee). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyReport submit(@RequestBody EmergencyReport report) {
        if (report.getEmployeeId() == 0 || report.getType() == null || report.getDescription() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required fields: employeeId, type, description");
        }
        return emergencyReportService.submitReport(report);
    }

    /** Get all emergency reports (manager view) or filtered by employee. */
    @GetMapping
    public List<EmergencyReport> listAll(@RequestParam(value = "employeeId", required = false) Integer employeeId) {
        if (employeeId != null) {
            return emergencyReportService.getByEmployeeId(employeeId);
        }
        return emergencyReportService.getAll();
    }

    @GetMapping("/employee/{id}")
    public List<EmergencyReport> getByEmployee(@PathVariable int id) {
        return emergencyReportService.getByEmployeeId(id);
    }

    /** Acknowledge an emergency (manager). */
    @PatchMapping("/{id}/acknowledge")
    public EmergencyReport acknowledge(@PathVariable int id) {
        try {
            return emergencyReportService.acknowledge(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    /** Resolve an emergency (manager). */
    @PatchMapping("/{id}/resolve")
    public EmergencyReport resolve(@PathVariable int id, @RequestBody(required = false) Map<String, String> body) {
        String responseText = (body != null) ? body.get("response") : null;
        try {
            return emergencyReportService.resolve(id, responseText);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
