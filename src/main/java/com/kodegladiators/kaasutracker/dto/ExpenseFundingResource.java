package com.kodegladiators.kaasutracker.dto;

import java.util.List;

public record ExpenseFundingResource(
        ExpenseResource expense,
        List<FundingAllocationResource> allocations
) {
}
