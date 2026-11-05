package com.kodegladiators.kaasutracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseDto(
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 10, fraction = 2)
        BigDecimal amount,

        @NotBlank
        @Size(max = 100)
        String category,

        @Size(max = 150)
        String merchant,

        @Size(max = 500)
        String description,
        @JsonFormat(pattern = "yyyy-MM-dd")
        @NotNull
        LocalDate expenseDate,

        @Size(max = 2048)
        String receiptUrl,

        boolean isConfirmed
) {
}
