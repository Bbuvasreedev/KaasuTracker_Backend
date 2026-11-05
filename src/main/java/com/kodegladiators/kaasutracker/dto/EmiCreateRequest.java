package com.kodegladiators.kaasutracker.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmiCreateRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull @Min(1) @Max(31) Integer dueDateDay,
        @NotNull LocalDate startDate,
        @NotNull @Min(1) @Max(1200) Integer durationMonths
) {
}
