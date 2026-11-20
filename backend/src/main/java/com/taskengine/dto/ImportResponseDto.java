package com.taskengine.dto;

import java.util.ArrayList;
import java.util.List;

public class ImportResponseDto {
    private int totalRecords;
    private int successfulRecords;
    private int duplicateRecords;
    private int invalidRecords;
    private int failedRecords;
    private List<String> errorMessages = new ArrayList<>();

    public ImportResponseDto() {}

    public ImportResponseDto(int totalRecords, int successfulRecords, int failedRecords, List<String> errorMessages) {
        this.totalRecords = totalRecords;
        this.successfulRecords = successfulRecords;
        this.failedRecords = failedRecords;
        this.errorMessages = errorMessages != null ? errorMessages : new ArrayList<>();
    }

    public ImportResponseDto(int totalRecords, int successfulRecords, int duplicateRecords, int invalidRecords, int failedRecords, List<String> errorMessages) {
        this.totalRecords = totalRecords;
        this.successfulRecords = successfulRecords;
        this.duplicateRecords = duplicateRecords;
        this.invalidRecords = invalidRecords;
        this.failedRecords = failedRecords;
        this.errorMessages = errorMessages != null ? errorMessages : new ArrayList<>();
    }

    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }

    public int getSuccessfulRecords() { return successfulRecords; }
    public void setSuccessfulRecords(int successfulRecords) { this.successfulRecords = successfulRecords; }

    public int getDuplicateRecords() { return duplicateRecords; }
    public void setDuplicateRecords(int duplicateRecords) { this.duplicateRecords = duplicateRecords; }

    public int getInvalidRecords() { return invalidRecords; }
    public void setInvalidRecords(int invalidRecords) { this.invalidRecords = invalidRecords; }

    public int getFailedRecords() { return failedRecords; }
    public void setFailedRecords(int failedRecords) { this.failedRecords = failedRecords; }

    public List<String> getErrorMessages() { return errorMessages; }
    public void setErrorMessages(List<String> errorMessages) { this.errorMessages = errorMessages; }
}
