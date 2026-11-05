package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;

public class UserDto {
    private String id;
    private String email;
    private String languagePref;
    private String themePref = "light";
    private BigDecimal baseSalary;
    private BigDecimal thresholdPercentage;

    public UserDto() {
    }

    public UserDto(String id, String email, String languagePref, String themePref,
                   BigDecimal baseSalary, BigDecimal thresholdPercentage) {
        this.id = id;
        this.email = email;
        this.languagePref = languagePref;
        this.themePref = (themePref == null || themePref.isBlank()) ? "light" : themePref;
        this.baseSalary = baseSalary;
        this.thresholdPercentage = thresholdPercentage;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLanguagePref() {
        return languagePref;
    }

    public void setLanguagePref(String languagePref) {
        this.languagePref = languagePref;
    }

    public String getThemePref() {
        return themePref == null || themePref.isBlank() ? "light" : themePref;
    }

    public void setThemePref(String themePref) {
        this.themePref = (themePref == null || themePref.isBlank()) ? "light" : themePref;
    }

    public BigDecimal getBaseSalary() {
        return baseSalary;
    }

    public void setBaseSalary(BigDecimal baseSalary) {
        this.baseSalary = baseSalary;
    }

    public BigDecimal getThresholdPercentage() {
        return thresholdPercentage;
    }

    public void setThresholdPercentage(BigDecimal thresholdPercentage) {
        this.thresholdPercentage = thresholdPercentage;
    }
}

