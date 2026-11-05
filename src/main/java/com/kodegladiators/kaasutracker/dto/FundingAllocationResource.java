package com.kodegladiators.kaasutracker.dto;

import com.kodegladiators.kaasutracker.model.FundingSource;
import java.math.BigDecimal;

public record FundingAllocationResource(
        Long id,
        FundingSource fundingSource,
        Long sourceId,
        BigDecimal amount
) {
}
