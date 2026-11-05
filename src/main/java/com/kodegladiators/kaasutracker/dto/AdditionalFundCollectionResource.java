package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;
import java.util.List;

public record AdditionalFundCollectionResource(
        List<AdditionalFundResource> funds,
        BigDecimal totalReceived,
        BigDecimal totalUsed,
        BigDecimal remainingAvailable
) {
}
