package com.kodegladiators.kaasutracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kodegladiators.kaasutracker.dto.ExpenseDto;
import com.kodegladiators.kaasutracker.dto.MonthlyFinancialSummary;
import com.kodegladiators.kaasutracker.dto.ReceiptExtractionResponse;
import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.model.MonthlyOpeningBalance;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.EmiRepository;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentRepository;
import com.kodegladiators.kaasutracker.repository.ExpenseFundingAllocationRepository;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.MonthlyOpeningBalanceRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import com.kodegladiators.kaasutracker.service.ExpenseService;
import com.kodegladiators.kaasutracker.service.EmiInstallmentProcessor;
import com.kodegladiators.kaasutracker.service.EmiSchedulerService;
import com.kodegladiators.kaasutracker.service.MonthlyFinanceService;
import com.kodegladiators.kaasutracker.service.ReceiptAiProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MonthlyFinanceIntegrationTests.TestAiConfiguration.class)
class MonthlyFinanceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private ExpenseFundingAllocationRepository allocationRepository;

    @Autowired
    private MonthlyOpeningBalanceRepository openingBalanceRepository;

    @Autowired
    private EmiRepository emiRepository;

    @Autowired
    @MockitoSpyBean
    private EmiInstallmentRepository installmentRepository;

    @Autowired
    @MockitoSpyBean
    private MonthlyFinanceService financeService;

    @Autowired
    @MockitoSpyBean
    private EmiInstallmentProcessor installmentProcessor;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private EmiSchedulerService emiSchedulerService;

    @Autowired
    private Clock clock;

    @Autowired
    private ExpenseService expenseService;

    @MockitoBean
    private ReceiptAiProvider receiptAiProvider;

    @Test
    void receiptExtractionRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/v1/receipts/extract")
                        .file(new MockMultipartFile(
                                "file", "receipt.jpg", "image/jpeg",
                                new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedReceiptUploadReturnsExtractionWithoutCreatingExpense() throws Exception {
        String userId = "receipt-test-" + UUID.randomUUID();
        when(receiptAiProvider.isConfigured()).thenReturn(true);
        when(receiptAiProvider.extract(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq("image/jpeg")))
                .thenReturn(new ReceiptExtractionResponse(
                        "Test merchant", LocalDate.of(2026, 5, 1), null,
                        new BigDecimal("12.50"), null, null, null, "INR",
                        "Food", null, null, null, 0.95, List.of()));

        mockMvc.perform(multipart("/api/v1/receipts/extract")
                        .file(new MockMultipartFile(
                                "file", "receipt.jpg", "image/jpeg",
                                new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}))
                        .with(jwt().jwt(token -> token.subject(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant").value("Test merchant"))
                .andExpect(jsonPath("$.totalAmount").value(12.50))
                .andExpect(jsonPath("$.receiptDate").value("2026-05-01"));

        verify(receiptAiProvider).extract(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq("image/jpeg"));
        assertTrue(expenseRepository.findAllByUserIdOrderByExpenseDateDesc(userId).isEmpty());
    }

    @Test
    void expenseConfirmationIdempotencyPreventsDuplicateReceiptSave() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        String request = """
                {
                  "amount":12.50,
                  "category":"Food",
                  "merchant":"Test merchant",
                  "description":"Reviewed receipt",
                  "expenseDate":"2026-05-01",
                  "isConfirmed":true
                }
                """;
        String key = "receipt-confirmation-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/expenses")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/expenses")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        assertEquals(1, expenseRepository.findAllByUserIdOrderByExpenseDateDesc(user.getId()).size());
    }

    @Test
    void allocatesSalaryBeforeCarryForwardAndBorrowedFunds() throws Exception {
        User user = createUser(new BigDecimal("7000.00"));
        String userId = user.getId();
        long carryForwardId = createOpeningBalance(user, new BigDecimal("1500.00"));
        long borrowedId = createFund("/api/v1/borrowed-funds", userId, """
                {"amount":2000.00,"fundDate":"2026-10-05","sourceDescription":"Family","purpose":"Emergency"}
                """);
        Expense expense = createExpense(user, "8500.00", "2026-10-15", true);

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"SALARY","amount":5000.00},
                                  {"fundingSource":"CARRY_FORWARD","sourceId":%d,"amount":1500.00},
                                  {"fundingSource":"BORROWED","sourceId":%d,"amount":2000.00}
                                ]}
                                """.formatted(carryForwardId, borrowedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expense.id").value(expense.getId()))
                .andExpect(jsonPath("$.allocations.length()").value(3))
                .andExpect(jsonPath("$.allocations[0].fundingSource").value("SALARY"))
                .andExpect(jsonPath("$.allocations[0].amount").value(5000.00));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(8500.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(5000.00))
                .andExpect(jsonPath("$.additionalFundsUsed").value(0.00))
                .andExpect(jsonPath("$.carryForwardUsed").value(1500.00))
                .andExpect(jsonPath("$.borrowedFundsUsed").value(2000.00))
                .andExpect(jsonPath("$.totalAllocatedExpenses").value(8500.00))
                .andExpect(jsonPath("$.unfundedOverspending").value(0.00));

        assertEquals(3, allocationRepository.findAllByExpenseId(expense.getId()).size());
    }

    @Test
    void borrowedFundAvailableBalanceUsesOutstandingRepaymentBalance() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        String userId = user.getId();
        long borrowedId = createFund("/api/v1/borrowed-funds", userId, """
                {"amount":500.00,"fundDate":"2026-10-05","sourceDescription":"Family",
                 "outstandingRepaymentBalance":300.00}
                """);
        createExpense(user, "100.00", "2026-10-15", true);
        financeService.recalculateMonthFunding(userId, 2026, 10);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowedFundsReceived").value(500.00))
                .andExpect(jsonPath("$.borrowedFundsUsed").value(100.00))
                .andExpect(jsonPath("$.remainingBorrowedFundsAvailable").value(200.00))
                .andExpect(jsonPath("$.totalAvailableFunding").value(200.00));

        mockMvc.perform(get("/api/v1/borrowed-funds?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds[0].id").value(borrowedId))
                .andExpect(jsonPath("$.funds[0].outstandingRepaymentBalance").value(300.00))
                .andExpect(jsonPath("$.funds[0].availableBalance").value(200.00))
                .andExpect(jsonPath("$.totalReceived").value(500.00))
                .andExpect(jsonPath("$.totalUsed").value(100.00))
                .andExpect(jsonPath("$.remainingAvailable").value(200.00));

        mockMvc.perform(get("/api/v1/borrowed-funds")
                        .with(jwt().jwt(token -> token.subject(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds[0].availableBalance").value(200.00))
                .andExpect(jsonPath("$.totalReceived").value(500.00))
                .andExpect(jsonPath("$.totalUsed").value(100.00))
                .andExpect(jsonPath("$.remainingAvailable").value(200.00));
    }

    @Test
    void replacesManualFundingAcrossSourcesAndRepeatedUpdatesDoNotDuplicateDeductions() throws Exception {
        User user = createUser(new BigDecimal("600.00"));
        long carryForwardId = createOpeningBalance(user, new BigDecimal("100.00"));
        long borrowedId = createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Borrowed"}
                """);
        Expense edited = createExpense(user, "600.00", "2026-10-08", true);
        createExpense(user, "100.00", "2026-10-09", true);

        String firstAllocation = """
                {"allocations":[
                  {"fundingSource":"SALARY","amount":300.00},
                  {"fundingSource":"CARRY_FORWARD","sourceId":%d,"amount":100.00},
                  {"fundingSource":"BORROWED","sourceId":%d,"amount":200.00}
                ]}
                """.formatted(carryForwardId, borrowedId);
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", edited.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstAllocation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expense.id").value(edited.getId()))
                .andExpect(jsonPath("$.allocations.length()").value(3));

        String revisedAllocation = """
                {"allocations":[
                  {"fundingSource":"SALARY","amount":400.00},
                  {"fundingSource":"CARRY_FORWARD","sourceId":%d,"amount":100.00},
                  {"fundingSource":"BORROWED","sourceId":%d,"amount":100.00}
                ]}
                """.formatted(carryForwardId, borrowedId);
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/v1/expenses/{id}/funding", edited.getId())
                            .with(jwt().jwt(token -> token.subject(user.getId())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(revisedAllocation))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allocations.length()").value(3));
        }

        assertEquals(3, allocationRepository.findAllByExpenseId(edited.getId()).size());
        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertAmount("500.00", summary.salaryFundedExpenses());
        assertAmount("100.00", summary.carryForwardUsed());
        assertAmount("100.00", summary.borrowedFundsUsed());
        assertAmount("100.00", summary.remainingSalaryAvailable());
        assertAmount("100.00", summary.remainingBorrowedFundsAvailable());
        assertFundingInvariant(summary);
    }

    @Test
    void replacingAllocationCanReuseThisExpensesExistingBorrowedFunds() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        long borrowedId = createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Borrowed"}
                """);
        Expense expense = createExpense(user, "200.00", "2026-10-08", true);
        var authenticated = jwt().jwt(token -> token.subject(user.getId()));

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(authenticated)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"BORROWED","sourceId":%d,"amount":200.00}
                                ]}
                                """.formatted(borrowedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations[0].amount").value(200.00));

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(authenticated)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"SALARY","amount":100.00},
                                  {"fundingSource":"BORROWED","sourceId":%d,"amount":100.00}
                                ]}
                                """.formatted(borrowedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations.length()").value(2));
    }

    @Test
    void rejectsFundingTotalsNegativeAmountsDuplicateSourcesAndForeignSourceIds() throws Exception {
        User owner = createUser(new BigDecimal("100.00"));
        User other = createUser(new BigDecimal("100.00"));
        Expense expense = createExpense(owner, "100.00", "2026-10-08", true);
        financeService.recalculateMonthFunding(owner.getId(), 2026, 10);
        long foreignFundId = createFund("/api/v1/borrowed-funds", other.getId(), """
                {"amount":100.00,"fundDate":"2026-10-05","sourceDescription":"Private"}
                """);

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(owner.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":99.00}]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(owner.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":-100.00}]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(owner.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"SALARY","amount":50.00},
                                  {"fundingSource":"SALARY","amount":50.00}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(owner.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"BORROWED","sourceId":%d,"amount":100.00}
                                ]}
                                """.formatted(foreignFundId)))
                .andExpect(status().isBadRequest());

        assertEquals(1, allocationRepository.findAllByExpenseId(expense.getId()).size());
        assertEquals(404, mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(other.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":100.00}]}
                                """))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void allocationRecalculationFailureRollsBackReplacementAndManualMarker() {
        User user = createUser(new BigDecimal("100.00"));
        Expense first = createExpense(user, "60.00", "2026-10-08", true);
        Expense edited = createExpense(user, "60.00", "2026-10-09", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);
        financeService.replaceAllocations(
                user.getId(),
                first.getId(),
                new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest(List.of(
                        new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest.AllocationItem(
                                com.kodegladiators.kaasutracker.model.FundingSource.SALARY,
                                null,
                                new BigDecimal("60.00")))));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () ->
                financeService.replaceAllocations(
                        user.getId(),
                        edited.getId(),
                        new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest(List.of(
                                new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest.AllocationItem(
                                        com.kodegladiators.kaasutracker.model.FundingSource.SALARY,
                                        null,
                                        new BigDecimal("60.00"))))));

        assertEquals(409, failure.getStatusCode().value());
        assertAmount("40.00", allocationRepository.findAllByExpenseId(edited.getId()).get(0).getAmount());
        assertEquals(false, expenseRepository.findById(edited.getId()).orElseThrow().isFundingAllocationManual());
        assertAmount("100.00", financeService.summary(user.getId(), 2026, 10).salaryFundedExpenses());
        assertAmount("20.00", financeService.summary(user.getId(), 2026, 10).unfundedOverspending());
    }

    @Test
    void fundingUpdateRequiresAuthenticationAndProtectsEmiLinkedExpenses() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        var installment = installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).orElseThrow();
        Expense emiExpense = expenseRepository.findByEmiInstallmentId(installment.getId()).orElseThrow();
        String request = """
                {"allocations":[{"fundingSource":"SALARY","amount":250.00}]}
                """;

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", emiExpense.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", emiExpense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict());
        assertTrue(expenseRepository.findById(emiExpense.getId()).isPresent());
    }

    @Test
    void monthlySummaryReturnsZeroTotalsForMonthWithoutExpenses() throws Exception {
        User user = createUser(BigDecimal.ZERO);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=9")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(9))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(0.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(0.00))
                .andExpect(jsonPath("$.carryForwardUsed").value(0.00))
                .andExpect(jsonPath("$.borrowedFundsUsed").value(0.00))
                .andExpect(jsonPath("$.unfundedOverspending").value(0.00))
                .andExpect(jsonPath("$.remainingSalaryAvailable").value(0.00));
    }

    @Test
    void monthlySummaryUsesBaseSalaryWhenMonthlySalaryAndOpeningBalanceAreMissing() throws Exception {
        User user = createUser(new BigDecimal("1250.75"));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=9")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseMonthlySalary").value(1250.75))
                .andExpect(jsonPath("$.openingCarryForwardBalance").value(0.00))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(0.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(0.00))
                .andExpect(jsonPath("$.unfundedOverspending").value(0.00))
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(0.00));
    }

    @Test
    void monthlySummaryUsesRequestedMonthWhenSwitchingPeriods() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        createExpense(user, "125.00", "2026-09-15", true);
        createExpense(user, "275.00", "2026-10-15", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 9);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=9")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(9))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(125.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(125.00));
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(10))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(275.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(275.00));
    }

    @Test
    void dueEmiProcessingFailureDoesNotHideOtherwiseValidMonthlySummary() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");
        doThrow(new IllegalStateException("simulated installment processing failure"))
                .when(installmentProcessor)
                .process(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(LocalDate.class),
                        org.mockito.ArgumentMatchers.any(LocalDate.class));
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(250.00))
                .andExpect(jsonPath("$.monthlyEmiPaidAmount").value(0.00))
                .andExpect(jsonPath("$.emiDueTodayUnpaidAmount").value(250.00))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(0.00));
        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).isEmpty());
    }

    @Test
    void monthlySummaryCalculationFailureReturnsSafeServerError() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        doThrow(new IllegalStateException("simulated calculation failure"))
                .when(financeService).summary(user.getId(), 2026, 10);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("simulated"))));
    }

    @Test
    void repositoryFailureDoesNotPreventFollowingSummaryRequest() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        doThrow(new DataAccessResourceFailureException("simulated database failure"))
                .when(installmentRepository)
                .findSnapshotsByUserIdAndDateRange(
                        user.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isInternalServerError());

        org.mockito.Mockito.reset(installmentRepository);
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(0.00));
    }

    @ParameterizedTest
    @CsvSource({
            "40000.00,40000.00,0.00,0.00,0.00,60000.00,10000.00,20000.00,30000.00",
            "60000.00,50000.00,10000.00,0.00,0.00,40000.00,0.00,10000.00,30000.00",
            "85000.00,50000.00,20000.00,15000.00,0.00,15000.00,0.00,0.00,15000.00",
            "110000.00,50000.00,20000.00,30000.00,10000.00,0.00,0.00,0.00,0.00"
    })
    void allocatesInSalaryCarryForwardBorrowedOrder(
            String expenseAmount,
            String expectedSalary,
            String expectedCarryForward,
            String expectedBorrowed,
            String expectedUnfunded,
            String expectedRemaining,
            String expectedSalaryRemaining,
            String expectedCarryRemaining,
            String expectedBorrowedRemaining
    ) throws Exception {
        User user = createUser(new BigDecimal("50000.00"));
        createOpeningBalance(user, new BigDecimal("20000.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":30000.00,"fundDate":"2026-10-05","sourceDescription":"Borrowed"}
                """);
        createExpense(user, expenseAmount, "2026-10-15", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertAmount(expectedSalary, summary.salaryFundedExpenses());
        assertAmount(expectedCarryForward, summary.carryForwardUsed());
        assertAmount(expectedBorrowed, summary.borrowedFundsUsed());
        assertAmount(expectedUnfunded, summary.unfundedOverspending());
        assertAmount(expectedRemaining, summary.totalAvailableFunding());
        assertAmount(expectedSalaryRemaining, summary.remainingSalaryAvailable());
        assertAmount(expectedCarryRemaining, summary.remainingCarryForwardAvailable());
        assertAmount(expectedBorrowedRemaining, summary.remainingBorrowedFundsAvailable());
        assertAmount(expenseAmount, summary.totalEligibleActualExpenses());
        assertFundingInvariant(summary);
    }

    @Test
    void expenseCreationApiPersistsPriorityAllocations() throws Exception {
        User user = createUser(new BigDecimal("50000.00"));
        createOpeningBalance(user, new BigDecimal("20000.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":30000.00,"fundDate":"2026-10-05","sourceDescription":"Borrowed"}
                """);

        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount":85000.00,
                                  "category":"Housing",
                                  "expenseDate":"2026-10-15",
                                  "isConfirmed":true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long expenseId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/v1/expenses/{id}/funding", expenseId)
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fundingSource").value("SALARY"))
                .andExpect(jsonPath("$[0].amount").value(50000.00))
                .andExpect(jsonPath("$[1].fundingSource").value("CARRY_FORWARD"))
                .andExpect(jsonPath("$[1].amount").value(20000.00))
                .andExpect(jsonPath("$[2].fundingSource").value("BORROWED"))
                .andExpect(jsonPath("$[2].amount").value(15000.00));
    }

    @Test
    void retrievesExpenseDetailsForOwnerAndHidesUnknownOrOtherUsersExpenses() throws Exception {
        User owner = createUser(new BigDecimal("1000.00"));
        User other = createUser(new BigDecimal("1000.00"));
        Expense expense = createExpense(owner, "42.50", "2026-10-12", true);
        expense.setCategory("Groceries");
        expense.setMerchant("Market");
        expense.setDescription("Weekly groceries");
        expense.setReceiptUrl("https://receipts.example/receipt-1");
        expenseRepository.saveAndFlush(expense);

        mockMvc.perform(get("/api/v1/expenses/{id}", expense.getId())
                        .with(jwt().jwt(token -> token.subject(owner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(expense.getId()))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.category").value("Groceries"))
                .andExpect(jsonPath("$.merchant").value("Market"))
                .andExpect(jsonPath("$.description").value("Weekly groceries"))
                .andExpect(jsonPath("$.expenseDate").value("2026-10-12"))
                .andExpect(jsonPath("$.receiptUrl").value("https://receipts.example/receipt-1"))
                .andExpect(jsonPath("$.isConfirmed").value(true));

        mockMvc.perform(get("/api/v1/expenses/{id}", expense.getId())
                        .with(jwt().jwt(token -> token.subject(other.getId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/expenses/{id}", Long.MAX_VALUE)
                        .with(jwt().jwt(token -> token.subject(owner.getId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/expenses/{id}", "not-a-number")
                        .with(jwt().jwt(token -> token.subject(owner.getId()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses/{id}", 0)
                        .with(jwt().jwt(token -> token.subject(owner.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingExpenseRestoresSalaryAndUpdatesDashboardAndRecentExpenses() throws Exception {
        User user = createUser(new BigDecimal("50000.00"));
        Expense expense = createExpense(user, "8000.00", "2026-10-08", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);
        assertAmount("42000.00", financeService.summary(user.getId(), 2026, 10).remainingSalaryAvailable());

        mockMvc.perform(delete("/api/v1/expenses/{id}", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isNoContent());

        assertTrue(expenseRepository.findById(expense.getId()).isEmpty());
        assertTrue(allocationRepository.findAllByExpenseId(expense.getId()).isEmpty());
        assertAmount("0.00", financeService.summary(user.getId(), 2026, 10).totalEligibleActualExpenses());
        assertAmount("0.00", financeService.summary(user.getId(), 2026, 10).salaryFundedExpenses());
        assertAmount("50000.00", financeService.summary(user.getId(), 2026, 10).remainingSalaryAvailable());
        assertTrue(expenseRepository.findAllByUserIdOrderByExpenseDateDesc(user.getId()).isEmpty());

        mockMvc.perform(delete("/api/v1/expenses/{id}", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletionRecalculatesPartiallyFundedAndUnfundedExpenseAndRestoresEverySource() throws Exception {
        User user = createUser(new BigDecimal("50000.00"));
        createOpeningBalance(user, new BigDecimal("10000.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":5000.00,"fundDate":"2026-10-02","sourceDescription":"Borrowed"}
                """);
        createExpense(user, "50000.00", "2026-10-01", true);
        Expense partiallyFunded = createExpense(user, "20000.00", "2026-10-03", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        MonthlyFinancialSummary beforeDelete = financeService.summary(user.getId(), 2026, 10);
        assertAmount("10000.00", beforeDelete.carryForwardUsed());
        assertAmount("5000.00", beforeDelete.borrowedFundsUsed());
        assertAmount("5000.00", beforeDelete.unfundedOverspending());

        mockMvc.perform(delete("/api/v1/expenses/{id}", partiallyFunded.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isNoContent());

        MonthlyFinancialSummary afterDelete = financeService.summary(user.getId(), 2026, 10);
        assertAmount("50000.00", afterDelete.totalEligibleActualExpenses());
        assertAmount("0.00", afterDelete.carryForwardUsed());
        assertAmount("0.00", afterDelete.borrowedFundsUsed());
        assertAmount("0.00", afterDelete.unfundedOverspending());
        assertAmount("10000.00", afterDelete.remainingCarryForwardAvailable());
        assertAmount("5000.00", afterDelete.remainingBorrowedFundsAvailable());
        assertFundingInvariant(afterDelete);
    }

    @Test
    void deletingExpenseRefreshesEstablishedFollowingMonthCarryForward() throws Exception {
        User user = createUser(new BigDecimal("5000.00"));
        Expense octoberExpense = createExpense(user, "3000.00", "2026-10-20", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);
        financeService.establishCarryForward(user.getId(), 2026, 11);

        mockMvc.perform(delete("/api/v1/expenses/{id}", octoberExpense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isNoContent());

        MonthlyOpeningBalance novemberOpening = openingBalanceRepository
                .findByUserIdAndYearAndMonth(user.getId(), 2026, 11).orElseThrow();
        assertAmount("5000.00", novemberOpening.getAmount());
    }

    @Test
    void deletingExpenseDoesNotDeleteOrMutateEmiScheduleAndDatabaseFailureRollsBack() {
        User user = createUser(new BigDecimal("1000.00"));
        Expense expense = createExpense(user, "100.00", "2026-10-08", true);
        doThrow(new IllegalStateException("simulated recalculation failure"))
                .when(financeService).recalculateMonthFunding(user.getId(), 2026, 10);

        assertThrows(IllegalStateException.class, () -> expenseService.delete(user.getId(), expense.getId()));
        assertTrue(expenseRepository.findById(expense.getId()).isPresent());
        assertTrue(allocationRepository.findAllByExpenseId(expense.getId()).isEmpty());

        org.mockito.Mockito.reset(financeService);
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        var installment = installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).orElseThrow();
        Expense emiExpense = expenseRepository.findByEmiInstallmentId(installment.getId()).orElseThrow();

        assertThrows(ResponseStatusException.class, () -> expenseService.delete(user.getId(), emiExpense.getId()));
        assertTrue(emiRepository.findById(emi.getId()).isPresent());
        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).isPresent());
        assertTrue(expenseRepository.findById(emiExpense.getId()).isPresent());
    }

    @Test
    void expenseCreationUsesRemainingSalaryBeforeCarryForwardAndBorrowedFunds() throws Exception {
        User user = createUser(new BigDecimal("50000.00"));
        long carryForwardId = createOpeningBalance(user, new BigDecimal("10000.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":5000.00,"fundDate":"2026-10-05","sourceDescription":"Borrowed"}
                """);

        long expenseToDate = createExpenseViaApi(user, "45000.00");
        long newExpenseId = createExpenseViaApi(user, "12000.00");

        mockMvc.perform(get("/api/v1/expenses/{id}/funding", newExpenseId)
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fundingSource").value("SALARY"))
                .andExpect(jsonPath("$[0].amount").value(5000.00))
                .andExpect(jsonPath("$[1].fundingSource").value("CARRY_FORWARD"))
                .andExpect(jsonPath("$[1].sourceId").value(carryForwardId))
                .andExpect(jsonPath("$[1].amount").value(7000.00))
                .andExpect(jsonPath("$[2]").doesNotExist());
        assertTrue(allocationRepository.findAllByExpenseId(expenseToDate).size() == 1);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryFundedExpenses").value(50000.00))
                .andExpect(jsonPath("$.carryForwardUsed").value(7000.00))
                .andExpect(jsonPath("$.borrowedFundsUsed").value(0.00))
                .andExpect(jsonPath("$.remainingSalaryAvailable").value(0.00))
                .andExpect(jsonPath("$.remainingCarryForwardAvailable").value(3000.00))
                .andExpect(jsonPath("$.remainingBorrowedFundsAvailable").value(5000.00))
                .andExpect(jsonPath("$.unfundedOverspending").value(0.00));
    }

    @Test
    void allocatesMultipleExpensesWithoutReusingBalancesAndReplacesAllocationsIdempotently() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        createOpeningBalance(user, new BigDecimal("50.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":30.00,"fundDate":"2026-10-04","sourceDescription":"Borrowed"}
                """);
        Expense first = createExpense(user, "80.00", "2026-10-01", true);
        Expense second = createExpense(user, "60.00", "2026-10-02", true);
        Expense third = createExpense(user, "70.00", "2026-10-03", true);

        financeService.recalculateMonthFunding(user.getId(), 2026, 10);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertEquals(new BigDecimal("100.00"), summary.salaryFundedExpenses());
        assertEquals(new BigDecimal("50.00"), summary.carryForwardUsed());
        assertEquals(new BigDecimal("30.00"), summary.borrowedFundsUsed());
        assertEquals(new BigDecimal("30.00"), summary.unfundedOverspending());
        assertEquals(1, allocationRepository.findAllByExpenseId(first.getId()).size());
        assertEquals(2, allocationRepository.findAllByExpenseId(second.getId()).size());
        assertEquals(2, allocationRepository.findAllByExpenseId(third.getId()).size());
        assertFundingInvariant(summary);
    }

    @Test
    void refundEditAndDeleteRecalculateTheMonthlyFunding() {
        User user = createUser(new BigDecimal("100.00"));
        Expense expense = createExpense(user, "80.00", "2026-10-08", true);

        financeService.recordRefund(user.getId(), expense.getId(),
                new com.kodegladiators.kaasutracker.dto.RefundRequest(new BigDecimal("30.00")));
        assertEquals(new BigDecimal("50.00"),
                financeService.summary(user.getId(), 2026, 10).salaryFundedExpenses());

        expenseService.update(user.getId(), expense.getId(), new ExpenseDto(
                new BigDecimal("120.00"), "Test", null, null, LocalDate.parse("2026-10-08"), null, true));
        MonthlyFinancialSummary editedSummary = financeService.summary(user.getId(), 2026, 10);
        assertEquals(new BigDecimal("90.00"), editedSummary.totalEligibleActualExpenses());
        assertEquals(new BigDecimal("90.00"), editedSummary.salaryFundedExpenses());
        assertAmount("0.00", editedSummary.unfundedOverspending());
        assertFundingInvariant(editedSummary);

        expenseService.delete(user.getId(), expense.getId());
        MonthlyFinancialSummary deletedSummary = financeService.summary(user.getId(), 2026, 10);
        assertAmount("0.00", deletedSummary.totalEligibleActualExpenses());
        assertAmount("0.00", deletedSummary.salaryFundedExpenses());
        assertTrue(allocationRepository.findAllByExpenseId(expense.getId()).isEmpty());
        assertFundingInvariant(deletedSummary);
    }

    @Test
    void handlesZeroBalancesAndLeavesAllExpenseUnfunded() {
        User user = createUser(BigDecimal.ZERO);
        Expense expense = createExpense(user, "25.50", "2026-10-08", true);

        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertTrue(allocationRepository.findAllByExpenseId(expense.getId()).isEmpty());
        assertEquals(new BigDecimal("25.50"), summary.unfundedOverspending());
        assertFundingInvariant(summary);
    }

    @Test
    void leavesExcessExpenseUnfundedWhenAllBalancesAreExhausted() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        Expense expense = createExpense(user, "150.00", "2026-10-08", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":150.00}]}
                                """))
                .andExpect(status().isConflict());

        assertEquals(new BigDecimal("100.00"),
                allocationRepository.findAllByExpenseId(expense.getId()).get(0).getAmount());
        assertEquals(new BigDecimal("50.00"),
                financeService.summary(user.getId(), 2026, 10).unfundedOverspending());
    }

    @Test
    void additionalFundsApiIsAvailableAndProtectedByAuthentication() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        Expense confirmedExpense = createExpense(user, "100.00", "2026-10-08", true);
        createExpense(user, "75.00", "2026-10-09", false);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        mockMvc.perform(post("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra income"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(200.00))
                .andExpect(jsonPath("$.sourceDescription").value("Bonus"))
                .andExpect(jsonPath("$.purpose").value("Extra income"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        mockMvc.perform(get("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds.length()").value(1))
                .andExpect(jsonPath("$.totalReceived").value(200.00))
                .andExpect(jsonPath("$.totalUsed").value(0.00))
                .andExpect(jsonPath("$.remainingAvailable").value(200.00));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=13")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(100.00));
        assertEquals(1, allocationRepository.findAllByExpenseId(confirmedExpense.getId()).size());
    }

    @Test
    void rejectsInvalidMonthlySummaryYearAndMonthParameters() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        var authenticated = jwt().jwt(token -> token.subject(user.getId()));

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=0&month=10").with(authenticated))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=0")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=not-a-month")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void carriesUnusedFundingIntoTheNextMonthOnlyOnce() throws Exception {
        User user = createUser(new BigDecimal("5000.00"));
        Expense octoberExpense = createExpense(user, "3000.00", "2026-10-20", true);
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", octoberExpense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":3000.00}]}
                                """))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/finance/carry-forward?year=2026&month=11")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(2000.00))
                .andReturn();
        JsonNode opening = objectMapper.readTree(result.getResponse().getContentAsString());
        long openingId = opening.get("id").asLong();

        mockMvc.perform(post("/api/v1/finance/carry-forward?year=2026&month=11")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(openingId))
                .andExpect(jsonPath("$.openingBalance").value(2000.00));

        user.setBaseSalary(BigDecimal.ZERO);
        userRepository.saveAndFlush(user);
        Expense novemberExpense = createExpense(user, "1500.00", "2026-11-03", true);
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", novemberExpense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"CARRY_FORWARD","sourceId":%d,"amount":1500.00}]}
                                """.formatted(openingId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=11")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryFundedExpenses").value(0.00))
                .andExpect(jsonPath("$.carryForwardUsed").value(1500.00))
                .andExpect(jsonPath("$.remainingCarryForwardAvailable").value(500.00));
    }

    @Test
    void editsToPreviousMonthRecalculateEstablishedCarryForward() {
        User user = createUser(new BigDecimal("5000.00"));
        Expense expense = createExpense(user, "3000.00", "2026-10-20", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);
        financeService.establishCarryForward(user.getId(), 2026, 11);

        expenseService.update(user.getId(), expense.getId(), new ExpenseDto(
                new BigDecimal("4000.00"), "Test", null, null, LocalDate.parse("2026-10-20"), null, true));

        MonthlyOpeningBalance novemberOpening = openingBalanceRepository
                .findByUserIdAndYearAndMonth(user.getId(), 2026, 11).orElseThrow();
        assertAmount("1000.00", novemberOpening.getAmount());
        assertAmount("1000.00", financeService.summary(user.getId(), 2026, 11).openingCarryForwardBalance());
    }

    @Test
    void refundsReverseFundingAndDoNotCountEmiSchedulesAsPaidExpenses() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Expense expense = createExpense(user, "100.00", "2026-10-08", true);
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":100.00}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/expenses/{id}/refunds", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":40.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRefundedAmount").value(40.00));

        Emi emi = Emi.builder().title("Car loan").amount(new BigDecimal("400.00"))
                .dueDateDay(18).durationMonths(24).startedOn(LocalDate.parse("2026-10-01"))
                .isActive(true).user(user).build();
        emiRepository.save(emi);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(60.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(60.00))
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(400.00))
                .andExpect(jsonPath("$.nextEmiDueDate").value("2026-10-18"))
                .andExpect(jsonPath("$.nextEmiPaymentStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.monthlyEmiPaidAmount").value(0.00));
    }

    @Test
    void processesDueTodayEmiAndReportsPaidPaymentInDashboard() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");

        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(250.00))
                .andExpect(jsonPath("$.monthlyEmiPaidAmount").value(250.00))
                .andExpect(jsonPath("$.emiDueTodayAmount").value(250.00))
                .andExpect(jsonPath("$.emiDueTodayUnpaidAmount").value(0.00))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(250.00))
                .andExpect(jsonPath("$.salaryFundedExpenses").value(250.00));

        var installment = installmentRepository.findByEmiIdAndDueDate(emi.getId(), LocalDate.parse("2026-10-10"))
                .orElseThrow();
        assertEquals(EmiInstallmentStatus.PAID, installment.getStatus());
        Expense paymentExpense = expenseRepository.findByEmiInstallmentId(installment.getId()).orElseThrow();
        assertAmount("250.00", paymentExpense.getAmount());
        assertEquals(1, allocationRepository.findAllByExpenseId(paymentExpense.getId()).size());

    }

    @Test
    void monthlySummaryDoesNotProcessDueEmiInstallments() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(250.00))
                .andExpect(jsonPath("$.emiDueTodayAmount").value(250.00))
                .andExpect(jsonPath("$.emiDueTodayUnpaidAmount").value(250.00))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(0.00));

        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"6", "12", "24", "36", "60"})
    void createsFixedDurationEmiAndGeneratesExactlyTheRequestedInstallments(int duration) throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        MvcResult result = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Phone",
                                  "amount":1250.00,
                                  "dueDateDay":31,
                                  "startDate":"2024-01-31",
                                  "durationMonths":%d
                                }
                                """.formatted(duration)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.startDate").value("2024-01-31"))
                .andExpect(jsonPath("$.durationMonths").value(duration))
                .andExpect(jsonPath("$.totalInstallments").value(duration))
                .andExpect(jsonPath("$.paidInstallments").value(0))
                .andExpect(jsonPath("$.remainingInstallments").value(duration))
                .andReturn();

        long emiId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        Emi emi = emiRepository.findById(emiId).orElseThrow();
        List<com.kodegladiators.kaasutracker.model.EmiInstallment> installments =
                installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId);
        assertEquals(duration, installments.size());
        assertEquals(installments.get(duration - 1).getDueDate(), emi.getEndDate());
    }

    @Test
    void clampsDueDayToLastCalendarDayForShortMonths() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        MvcResult result = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Long term",
                                  "amount":10.00,
                                  "dueDateDay":31,
                                  "startDate":"2024-01-31",
                                  "durationMonths":3
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endDate").value("2024-03-31"))
                .andReturn();
        long emiId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        List<com.kodegladiators.kaasutracker.model.EmiInstallment> installments =
                installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId);
        assertEquals(List.of(
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 31)
        ), installments.stream().map(com.kodegladiators.kaasutracker.model.EmiInstallment::getDueDate).toList());
    }

    @Test
    void nonLeapFebruaryUsesItsLastDayForDay29Schedule() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        MvcResult result = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"February loan",
                                  "amount":10.00,
                                  "dueDateDay":29,
                                  "startDate":"2023-02-01",
                                  "durationMonths":2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endDate").value("2023-03-29"))
                .andReturn();
        long emiId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertEquals(List.of(LocalDate.of(2023, 2, 28), LocalDate.of(2023, 3, 29)),
                installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId).stream()
                        .map(com.kodegladiators.kaasutracker.model.EmiInstallment::getDueDate).toList());
    }

    @Test
    void emiCreationIsIdempotentWhenRequestUsesSameKey() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        String body = """
                {"title":"Car loan","amount":500.00,"dueDateDay":15,
                 "startDate":"2026-10-01","durationMonths":12}
                """;
        MvcResult first = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .header("Idempotency-Key", "car-loan-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        MvcResult retry = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .header("Idempotency-Key", "car-loan-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(firstId, objectMapper.readTree(retry.getResponse().getContentAsString()).get("id").asLong());
        assertEquals(1, emiRepository.findAllByUserId(user.getId()).size());
        assertEquals(12, installmentRepository.findAllByEmiIdOrderByDueDateAsc(firstId).size());
        mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .header("Idempotency-Key", "car-loan-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("500.00", "600.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidEmiTermsAndClientRequestedCompletion() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Invalid","amount":10.00,"dueDateDay":31,
                                 "startDate":"2026-10-01","durationMonths":0}
                                """))
                .andExpect(status().isBadRequest());

        long emiId = createEmiViaApi(user, """
                {"title":"Valid","amount":10.00,"dueDateDay":31,
                 "startDate":"2026-10-01","durationMonths":2}
                """);
        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanDeactivateAndReactivateEmiWithoutRemovingInstallments() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        long emiId = createEmiViaApi(user, """
                {"title":"Loan","amount":250.00,"dueDateDay":10,
                 "startDate":"2026-10-01","durationMonths":6}
                """);
        long installmentCount = installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId).size();

        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.isActive").value(false));
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        assertTrue(expenseRepository.findByEmiInstallmentId(
                installmentRepository.findByEmiIdAndDueDate(emiId, LocalDate.parse("2026-10-10"))
                        .orElseThrow().getId()).isEmpty());

        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));

        assertEquals(installmentCount, installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId).size());
        var dueInstallment = installmentRepository.findByEmiIdAndDueDate(
                emiId, LocalDate.parse("2026-10-10")).orElseThrow();
        assertEquals(EmiInstallmentStatus.PAID, dueInstallment.getStatus());
        assertTrue(expenseRepository.findByEmiInstallmentId(dueInstallment.getId()).isPresent());
        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        var persistedEmi = emiRepository.findById(emiId).orElseThrow();
        assertEquals(EmiStatus.INACTIVE, persistedEmi.getStatus());
        assertEquals(false, persistedEmi.isActive());
        assertEquals(EmiInstallmentStatus.PAID,
                installmentRepository.findByEmiIdAndDueDate(emiId, LocalDate.parse("2026-10-10"))
                        .orElseThrow().getStatus());
        assertTrue(expenseRepository.findByEmiInstallmentId(dueInstallment.getId()).isPresent());
    }

    @Test
    void emiStatusEndpointRequiresAuthenticationAndValidOwnedEmiId() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        long emiId = createEmiViaApi(user, """
                {"title":"Loan","amount":100.00,"dueDateDay":10,
                 "startDate":"2026-10-01","durationMonths":6}
                """);

        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/emis/{id}/status", 0)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/emis/{id}/status", -1)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/emis/{id}/status", 999999)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyOwnerMayUpdateEmiStatusAndCompletedEmiCannotBeReactivated() throws Exception {
        User owner = createUser(new BigDecimal("1000.00"));
        User other = createUser(new BigDecimal("1000.00"));
        long emiId = createEmiViaApi(owner, """
                {"title":"Short loan","amount":20.00,"dueDateDay":10,
                 "startDate":"2026-10-01","durationMonths":1}
                """);

        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(other.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound());

        emiSchedulerService.processDueInstallmentsForUser(owner.getId(), LocalDate.now(clock));
        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(owner.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(owner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].paidInstallments").value(1))
                .andExpect(jsonPath("$[0].remainingInstallments").value(0));
        assertEquals(1, expenseRepository.findByEmiInstallmentId(
                installmentRepository.findByEmiIdAndDueDate(emiId, LocalDate.parse("2026-10-10"))
                        .orElseThrow().getId()).stream().count());
    }

    @Test
    void inactiveEmiWithUnknownDurationIsExcludedFromAutomaticProcessing() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi legacy = createEmi(user, "50.00", 10, "2026-10-01");
        mockMvc.perform(put("/api/v1/emis/{id}/status", legacy.getId())
                .with(jwt().jwt(token -> token.subject(user.getId())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                legacy.getId(), LocalDate.parse("2026-10-10")).isEmpty());
    }

    @Test
    void inactiveEmiRetainsPaidHistoryButNoLongerAddsFutureObligations() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        long emiId = createEmiViaApi(user, """
                {"title":"Two month loan","amount":75.00,"dueDateDay":10,
                 "startDate":"2026-10-01","durationMonths":2}
                """);
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        mockMvc.perform(put("/api/v1/emis/{id}/status", emiId)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyEmiScheduledAmount").value(75.00))
                .andExpect(jsonPath("$.monthlyEmiPaidAmount").value(75.00))
                .andExpect(jsonPath("$.totalEligibleActualExpenses").value(75.00))
                .andExpect(jsonPath("$.nextEmiDueDate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void legacyEmiDurationIsExplicitlyUnknownUntilOwnerSuppliesTerms() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi legacy = createEmi(user, "50.00", 31, "2024-01-31");
        legacy.setDurationMonths(12);
        legacy.setDurationKnown(false);
        legacy.setEndDate(null);
        legacy.setTotalInstallments(null);
        emiRepository.saveAndFlush(legacy);

        mockMvc.perform(get("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].durationMonths").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].endDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].totalInstallments").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].remainingInstallments").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(put("/api/v1/emis/{id}/duration", legacy.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationMonths\":6,\"startDate\":\"2024-01-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMonths").value(6))
                .andExpect(jsonPath("$.totalInstallments").value(6))
                .andExpect(jsonPath("$.endDate").value("2024-06-30"))
                .andExpect(jsonPath("$.remainingInstallments").value(6));
        assertEquals(6, installmentRepository.findAllByEmiIdOrderByDueDateAsc(legacy.getId()).size());
    }

    @Test
    void doesNotProcessFutureEmiButProcessesOverdueInstallment() {
        User user = createUser(new BigDecimal("1000.00"));
        Emi futureEmi = createEmi(user, "100.00", 15, "2026-10-01");
        Emi overdueEmi = createEmi(user, "200.00", 5, "2026-10-01");

        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));

        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                futureEmi.getId(), LocalDate.parse("2026-10-15")).isEmpty());
        assertEquals(EmiInstallmentStatus.PAID, installmentRepository.findByEmiIdAndDueDate(
                overdueEmi.getId(), LocalDate.parse("2026-10-05")).orElseThrow().getStatus());
        assertTrue(expenseRepository.findAllByUserIdAndExpenseDateBetweenOrderByExpenseDateAscIdAsc(
                user.getId(), LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-31")).isEmpty());
    }

    @Test
    void allocatesEmiAcrossAllAvailableSourcesAndReportsUnfundedRemainder() throws Exception {
        User user = createUser(new BigDecimal("10000.00"));
        createExpense(user, "5000.00", "2026-10-01", true);
        createOpeningBalance(user, new BigDecimal("8000.00"));
        createFund("/api/v1/borrowed-funds", user.getId(), """
                {"amount":4000.00,"fundDate":"2026-10-02","sourceDescription":"Borrowed"}
                """);
        Emi emi = createEmi(user, "20000.00", 10, "2026-10-01");

        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));

        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertAmount("10000.00", summary.salaryFundedExpenses());
        assertAmount("8000.00", summary.carryForwardUsed());
        assertAmount("4000.00", summary.borrowedFundsUsed());
        assertAmount("3000.00", summary.unfundedOverspending());
        assertAmount("0.00", summary.remainingSalaryAvailable());
        assertAmount("0.00", summary.remainingCarryForwardAvailable());
        assertAmount("0.00", summary.remainingBorrowedFundsAvailable());
        assertFundingInvariant(summary);
        var installment = installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).orElseThrow();
        Expense payment = expenseRepository.findByEmiInstallmentId(installment.getId()).orElseThrow();
        assertEquals(3, allocationRepository.findAllByExpenseId(payment.getId()).size());
    }

    @Test
    void repeatedSchedulerRunsDoNotDuplicateInstallmentExpenseOrAllocation() {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");

        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));
        emiSchedulerService.processDueInstallmentsForUser(user.getId(), LocalDate.now(clock));

        var installment = installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).orElseThrow();
        assertEquals(EmiInstallmentStatus.PAID, installment.getStatus());
        assertEquals(1, expenseRepository.findAllByUserIdAndExpenseDateBetweenOrderByExpenseDateAscIdAsc(
                user.getId(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")).size());
        assertEquals(1, allocationRepository.findAllByExpenseId(
                expenseRepository.findByEmiInstallmentId(installment.getId()).orElseThrow().getId()).size());
    }

    @Test
    void concurrentSchedulerRunsCreateOnlyOneEmiPayment() throws Exception {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                awaitAndProcess(start, user.getId());
                return null;
            });
            Future<?> second = executor.submit(() -> {
                awaitAndProcess(start, user.getId());
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        var installment = installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).orElseThrow();
        assertEquals(EmiInstallmentStatus.PAID, installment.getStatus());
        assertEquals(1, expenseRepository.findByEmiInstallmentId(installment.getId()).stream().count());
    }

    @Test
    void transactionRollsBackEmiInstallmentAndExpenseWhenAllocationFails() {
        User user = createUser(new BigDecimal("1000.00"));
        Emi emi = createEmi(user, "250.00", 10, "2026-10-01");
        doThrow(new IllegalStateException("simulated allocation failure"))
                .when(financeService).recalculateMonthFunding(user.getId(), 2026, 10);

        assertThrows(IllegalStateException.class, () -> installmentProcessor.process(
                emi.getId(), LocalDate.parse("2026-10-10"), LocalDate.now(clock)));

        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                emi.getId(), LocalDate.parse("2026-10-10")).isEmpty());
        assertTrue(expenseRepository.findAllByUserIdAndExpenseDateBetweenOrderByExpenseDateAscIdAsc(
                user.getId(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")).isEmpty());
    }

    @Test
    void dueInstallmentProcessingIsScopedToTheRequestedUser() {
        User firstUser = createUser(new BigDecimal("1000.00"));
        User secondUser = createUser(new BigDecimal("1000.00"));
        Emi firstUserEmi = createEmi(firstUser, "100.00", 10, "2026-10-01");
        Emi secondUserEmi = createEmi(secondUser, "200.00", 15, "2026-10-01");

        emiSchedulerService.processDueInstallmentsForUser(firstUser.getId(), LocalDate.now(clock));

        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                firstUserEmi.getId(), LocalDate.parse("2026-10-10")).isPresent());
        assertTrue(installmentRepository.findByEmiIdAndDueDate(
                secondUserEmi.getId(), LocalDate.parse("2026-10-15")).isEmpty());
        assertAmount("100.00", financeService.summary(firstUser.getId(), 2026, 10).monthlyEmiPaidAmount());
        assertAmount("0.00", financeService.summary(secondUser.getId(), 2026, 10).monthlyEmiPaidAmount());
    }

    @Test
    void requiresAuthenticationAndScopesExpenseFundingToTheOwner() throws Exception {
        User owner = createUser(new BigDecimal("1000.00"));
        User other = createUser(new BigDecimal("1000.00"));
        Expense expense = createExpense(owner, "50.00", "2026-10-08", true);

        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.when(jwtDecoder.decode("invalid-token"))
                .thenThrow(new BadJwtException("Invalid JWT"));
        mockMvc.perform(get("/api/v1/finance/monthly-summary?year=2026&month=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(other.getId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(other.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"SALARY","amount":50.00}]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void serializesConcurrentAllocationsAgainstTheSameSalaryBalance() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        Expense first = createExpense(user, "100.00", "2026-10-08", true);
        Expense second = createExpense(user, "100.00", "2026-10-09", true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstResult = executor.submit(() -> tryAllocate(user.getId(), first.getId(), start));
            Future<Boolean> secondResult = executor.submit(() -> tryAllocate(user.getId(), second.getId(), start));
            start.countDown();
            assertTrue(firstResult.get() ^ secondResult.get());
        } finally {
            executor.shutdownNow();
        }
        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertEquals(new BigDecimal("100.00"), summary.salaryFundedExpenses());
        assertEquals(new BigDecimal("100.00"), summary.unfundedOverspending());
    }

    private boolean tryAllocate(String userId, long expenseId, CountDownLatch start) throws Exception {
        start.await();
        try {
            financeService.replaceAllocations(
                    userId,
                    expenseId,
                    new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest(List.of(
                            new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest.AllocationItem(
                                    com.kodegladiators.kaasutracker.model.FundingSource.SALARY,
                                    null,
                                    new BigDecimal("100.00"))))
            );
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(409, exception.getStatusCode().value());
            return false;
        }
    }

    private void awaitAndProcess(CountDownLatch start, String userId) {
        try {
            start.await();
            emiSchedulerService.processDueInstallmentsForUser(userId, LocalDate.now(clock));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting concurrent EMI processing", exception);
        }
    }

    private long createFund(String path, String userId, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .with(jwt().jwt(token -> token.subject(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createEmiViaApi(User user, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/emis")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Emi createEmi(User user, String amount, int dueDay, String startedOn) {
        Emi emi = Emi.builder()
                .title("Test EMI")
                .amount(new BigDecimal(amount))
                .dueDateDay(dueDay)
                .durationMonths(12)
                .startedOn(LocalDate.parse(startedOn))
                .isActive(true)
                .user(user)
                .build();
        return emiRepository.saveAndFlush(emi);
    }

    private long createExpenseViaApi(User user, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/expenses")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount":%s,
                                  "category":"Test",
                                  "expenseDate":"2026-10-15",
                                  "isConfirmed":true
                                }
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createOpeningBalance(User user, BigDecimal amount) {
        MonthlyOpeningBalance opening = new MonthlyOpeningBalance();
        opening.setUser(user);
        opening.setYear(2026);
        opening.setMonth(10);
        opening.setAmount(amount);
        return openingBalanceRepository.saveAndFlush(opening).getId();
    }

    private static void assertFundingInvariant(MonthlyFinancialSummary summary) {
        BigDecimal assigned = summary.salaryFundedExpenses()
                .add(summary.carryForwardUsed())
                .add(summary.borrowedFundsUsed())
                .add(summary.unfundedOverspending());
        assertEquals(0, summary.totalEligibleActualExpenses().compareTo(assigned));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private User createUser(BigDecimal baseSalary) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-only");
        user.setBaseSalary(baseSalary);
        return userRepository.saveAndFlush(user);
    }

    private Expense createExpense(User user, String amount, String date, boolean confirmed) {
        Expense expense = new Expense();
        expense.setUser(user);
        expense.setAmount(new BigDecimal(amount));
        expense.setCategory("Test");
        expense.setExpenseDate(LocalDate.parse(date));
        expense.setConfirmed(confirmed);
        return expenseRepository.saveAndFlush(expense);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAiConfiguration {

        @Bean
        ChatModel testChatModel() {
            return org.mockito.Mockito.mock(ChatModel.class);
        }

        @Bean
        JwtDecoder testJwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneId.of("UTC"));
        }
    }
}
