package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseAnalysisResponse(
        BigDecimal amount,
        String merchant,
        String category,
        LocalDate date,
        Double confidenceScore
) {
}
