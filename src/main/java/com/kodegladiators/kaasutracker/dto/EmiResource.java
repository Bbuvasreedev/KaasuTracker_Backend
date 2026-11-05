package com.kodegladiators.kaasutracker.dto;

import com.kodegladiators.kaasutracker.model.EmiStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmiResource(
        Long id,
        String title,
        BigDecimal amount,
        Integer dueDateDay,
        boolean isActive,
        EmiStatus status,
        LocalDate startDate,
        Integer durationMonths,
        LocalDate endDate,
        Integer totalInstallments,
        long paidInstallments,
        Integer remainingInstallments
) {
}
