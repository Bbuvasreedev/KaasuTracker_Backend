package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;
import java.util.List;

public record FundCollectionResource(
        List<FundResource> funds,
        BigDecimal totalReceived,
        BigDecimal totalUsed,
        BigDecimal remainingAvailable
) {
}
