package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmiInstallmentSnapshot(
        Long emiId,
        LocalDate dueDate,
        BigDecimal amount,
        EmiInstallmentStatus status
) {
}
