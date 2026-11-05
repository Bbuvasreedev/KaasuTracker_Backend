package com.kodegladiators.kaasutracker.dto;

import com.kodegladiators.kaasutracker.model.FundStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AdditionalFundResource(
        Long id,
        BigDecimal amount,
        LocalDate fundDate,
        String sourceDescription,
        String purpose,
        FundStatus status
) {
}
