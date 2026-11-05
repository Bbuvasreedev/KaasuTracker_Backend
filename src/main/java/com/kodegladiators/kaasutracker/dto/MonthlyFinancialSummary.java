package com.kodegladiators.kaasutracker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyFinancialSummary(
        int month,
        int year,
        BigDecimal baseMonthlySalary,
        BigDecimal openingCarryForwardBalance,
        BigDecimal additionalFundsReceived,
        BigDecimal borrowedFundsReceived,
        BigDecimal totalEligibleActualExpenses,
        BigDecimal salaryFundedExpenses,
        BigDecimal additionalFundsUsed,
        BigDecimal carryForwardUsed,
        BigDecimal borrowedFundsUsed,
        BigDecimal totalAllocatedExpenses,
        BigDecimal remainingSalaryAvailable,
        BigDecimal remainingCarryForwardAvailable,
        BigDecimal remainingAdditionalFundsAvailable,
        BigDecimal remainingBorrowedFundsAvailable,
        BigDecimal totalAvailableFunding,
        BigDecimal unfundedOverspending,
        BigDecimal monthlyEmiScheduledAmount,
        LocalDate nextEmiDueDate,
        String nextEmiPaymentStatus,
        BigDecimal monthlyEmiPaidAmount,
        BigDecimal emiDueTodayAmount,
        BigDecimal emiDueTodayUnpaidAmount
) {
}
