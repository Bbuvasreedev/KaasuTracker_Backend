package com.kodegladiators.kaasutracker.dto;

import com.kodegladiators.kaasutracker.model.FundingSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record FundingAllocationRequest(
        @NotNull List<@NotNull @Valid AllocationItem> allocations
) {
    public record AllocationItem(
            @NotNull FundingSource fundingSource,
            @Positive Long sourceId,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 10, fraction = 2)
            BigDecimal amount
    ) {
    }
}
