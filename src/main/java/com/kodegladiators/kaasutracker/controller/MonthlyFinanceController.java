package com.kodegladiators.kaasutracker.controller;

import com.kodegladiators.kaasutracker.dto.AdditionalFundCollectionResource;
import com.kodegladiators.kaasutracker.dto.AdditionalFundRequest;
import com.kodegladiators.kaasutracker.dto.AdditionalFundResource;
import com.kodegladiators.kaasutracker.dto.FundCollectionResource;
import com.kodegladiators.kaasutracker.dto.CarryForwardResource;
import com.kodegladiators.kaasutracker.dto.FundRequest;
import com.kodegladiators.kaasutracker.dto.FundResource;
import com.kodegladiators.kaasutracker.dto.ExpenseFundingResource;
import com.kodegladiators.kaasutracker.dto.FundingAllocationRequest;
import com.kodegladiators.kaasutracker.dto.FundingAllocationResource;
import com.kodegladiators.kaasutracker.dto.MonthlyFinancialSummary;
import com.kodegladiators.kaasutracker.dto.MonthlySalaryRequest;
import com.kodegladiators.kaasutracker.dto.RefundRequest;
import com.kodegladiators.kaasutracker.service.MonthlyFinanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class MonthlyFinanceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonthlyFinanceController.class);

    private final MonthlyFinanceService financeService;
    private final HikariDataSource dataSource;

    public MonthlyFinanceController(
            MonthlyFinanceService financeService,
            HikariDataSource dataSource
    ) {
        this.financeService = financeService;
        this.dataSource = dataSource;
    }

    @GetMapping("/finance/monthly-summary")
    public MonthlyFinancialSummary summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1) @Max(9999) int year,
            @RequestParam @Min(1) @Max(12) int month
    ) {
        long startedAt = System.nanoTime();
        try {
            return financeService.summary(jwt.getSubject(), year, month);
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
            int active = pool == null ? -1 : pool.getActiveConnections();
            int idle = pool == null ? -1 : pool.getIdleConnections();
            int waiting = pool == null ? -1 : pool.getThreadsAwaitingConnection();
            if (elapsedMillis >= 5_000) {
                LOGGER.warn(
                        "Monthly summary took {} ms; JDBC pool active={}, idle={}, waiting={}",
                        elapsedMillis,
                        active,
                        idle,
                        waiting
                );
            } else {
                LOGGER.debug(
                        "Monthly summary took {} ms; JDBC pool active={}, idle={}, waiting={}",
                        elapsedMillis,
                        active,
                        idle,
                        waiting
                );
            }
        }
    }

    @PutMapping("/finance/monthly-salary")
    public MonthlySalaryRequest saveSalary(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MonthlySalaryRequest request
    ) {
        return financeService.saveSalary(jwt.getSubject(), request);
    }

    @PostMapping("/finance/carry-forward")
    public CarryForwardResource establishCarryForward(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1) @Max(9999) int year,
            @RequestParam @Min(1) @Max(12) int month
    ) {
        return financeService.establishCarryForward(jwt.getSubject(), year, month);
    }

    @GetMapping("/finance/carry-forward")
    public CarryForwardResource carryForward(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1) @Max(9999) int year,
            @RequestParam @Min(1) @Max(12) int month
    ) {
        return financeService.carryForward(jwt.getSubject(), year, month);
    }

    @GetMapping("/borrowed-funds")
    public FundCollectionResource borrowedFunds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        return financeService.borrowedFunds(jwt.getSubject(), year, month);
    }

    @PostMapping("/borrowed-funds")
    public ResponseEntity<FundResource> createBorrowedFund(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FundRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.createBorrowedFund(jwt.getSubject(), request));
    }

    @PutMapping("/borrowed-funds/{id}")
    public FundResource updateBorrowedFund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody FundRequest request
    ) {
        return financeService.updateBorrowedFund(jwt.getSubject(), id, request);
    }

    @DeleteMapping("/borrowed-funds/{id}")
    public ResponseEntity<Void> deactivateBorrowedFund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id
    ) {
        financeService.deactivateBorrowedFund(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/additional-funds")
    public AdditionalFundCollectionResource additionalFunds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        return financeService.additionalFunds(jwt.getSubject(), year, month);
    }

    @PostMapping("/additional-funds")
    public ResponseEntity<AdditionalFundResource> createAdditionalFund(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AdditionalFundRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.createAdditionalFund(jwt.getSubject(), request));
    }

    @PutMapping("/additional-funds/{id}")
    public AdditionalFundResource updateAdditionalFund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @Valid @RequestBody AdditionalFundRequest request
    ) {
        return financeService.updateAdditionalFund(jwt.getSubject(), id, request);
    }

    @DeleteMapping("/additional-funds/{id}")
    public ResponseEntity<Void> deactivateAdditionalFund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id
    ) {
        financeService.deactivateAdditionalFund(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/expenses/{expenseId}/funding")
    public ExpenseFundingResource replaceAllocations(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long expenseId,
            @NotNull @Valid @RequestBody FundingAllocationRequest request
    ) {
        return financeService.replaceAllocations(jwt.getSubject(), expenseId, request);
    }

    @GetMapping("/expenses/{expenseId}/funding")
    public List<FundingAllocationResource> allocations(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long expenseId
    ) {
        return financeService.allocations(jwt.getSubject(), expenseId);
    }

    @PostMapping("/expenses/{expenseId}/refunds")
    public ResponseEntity<RefundResponse> refund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long expenseId,
            @Valid @RequestBody RefundRequest request
    ) {
        BigDecimal refundedAmount = financeService.recordRefund(jwt.getSubject(), expenseId, request);
        return ResponseEntity.ok(new RefundResponse(expenseId, refundedAmount));
    }

    public record RefundResponse(long expenseId, BigDecimal totalRefundedAmount) {
    }
}
