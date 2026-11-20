package com.taskengine.controller;

import com.taskengine.model.Role;
import com.taskengine.model.UserAccount;
import com.taskengine.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        Optional<UserAccount> accountOpt = userAccountRepository.findByUsername(username.trim().toLowerCase());
        if (accountOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        UserAccount account = accountOpt.get();
        if (!passwordEncoder.matches(password, account.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", account.getId());
        response.put("username", account.getUsername());
        response.put("role", account.getRole().name());

        if (account.getEmployee() != null) {
            response.put("employeeId", account.getEmployee().getId());
            response.put("employeeName", account.getEmployee().getName());
            response.put("employeeRole", account.getEmployee().getRole());
            response.put("skills", account.getEmployee().getSkills());
            response.put("workloadHours", account.getEmployee().getWorkloadHours());
            response.put("maxWorkload", account.getEmployee().getMaxWorkload());
            response.put("available", account.getEmployee().isAvailable());
        } else {
            response.put("employeeId", null);
            response.put("employeeName", "Manager Administrator");
        }

        String rawAuth = account.getUsername() + ":" + password;
        String encodedAuth = "Basic " + Base64.getEncoder().encodeToString(rawAuth.getBytes(StandardCharsets.UTF_8));
        response.put("authHeader", encodedAuth);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String username = authentication.getName();
        UserAccount account = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Map<String, Object> response = new HashMap<>();
        response.put("id", account.getId());
        response.put("username", account.getUsername());
        response.put("role", account.getRole().name());

        if (account.getEmployee() != null) {
            response.put("employeeId", account.getEmployee().getId());
            response.put("employeeName", account.getEmployee().getName());
            response.put("employeeRole", account.getEmployee().getRole());
            response.put("skills", account.getEmployee().getSkills());
            response.put("workloadHours", account.getEmployee().getWorkloadHours());
            response.put("maxWorkload", account.getEmployee().getMaxWorkload());
            response.put("available", account.getEmployee().isAvailable());
        } else {
            response.put("employeeId", null);
            response.put("employeeName", "Manager Administrator");
        }

        return ResponseEntity.ok(response);
    }
}
