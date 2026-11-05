package com.kodegladiators.kaasutracker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record EmiDurationRequest(
        @NotNull @Min(1) @Max(1200) Integer durationMonths,
        LocalDate startDate
) {
}
