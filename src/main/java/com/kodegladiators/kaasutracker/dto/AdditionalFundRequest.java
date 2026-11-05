package com.kodegladiators.kaasutracker.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AdditionalFundRequest(
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 10, fraction = 2)
        BigDecimal amount,
        @NotNull LocalDate fundDate,
        @NotBlank @Size(max = 200) String sourceDescription,
        @Size(max = 500) String purpose
) {
}
