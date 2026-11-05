package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;

public record CarryForwardResource(
        Long id,
        int year,
        int month,
        BigDecimal openingBalance,
        BigDecimal calculatedEligibleClosingBalance,
        MonthlyFinancialSummary monthlySummary
) {
}
