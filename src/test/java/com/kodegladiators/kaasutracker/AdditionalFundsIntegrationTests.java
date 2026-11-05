package com.kodegladiators.kaasutracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kodegladiators.kaasutracker.dto.MonthlyFinancialSummary;
import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.model.FundingSource;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.ExpenseFundingAllocationRepository;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.MonthlyOpeningBalanceRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import com.kodegladiators.kaasutracker.service.MonthlyFinanceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MonthlyFinanceIntegrationTests.TestAiConfiguration.class)
class AdditionalFundsIntegrationTests {

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
    private MonthlyFinanceService financeService;

    @Test
    void returnsEmptyCollectionWhenUserHasNoAdditionalFunds() throws Exception {
        User user = createUser(BigDecimal.ZERO);

        mockMvc.perform(get("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds.length()").value(0))
                .andExpect(jsonPath("$.totalReceived").value(0.00))
                .andExpect(jsonPath("$.totalUsed").value(0.00))
                .andExpect(jsonPath("$.remainingAvailable").value(0.00));
    }

    @Test
    void returnsAdditionalFundsForUserWithRecords() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra income"}
                """);
        createAdditionalFundViaApi(user, """
                {"amount":350.00,"fundDate":"2026-10-12","sourceDescription":"Refund","purpose":"Tax refund"}
                """);

        mockMvc.perform(get("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds.length()").value(2))
                .andExpect(jsonPath("$.totalReceived").value(550.00))
                .andExpect(jsonPath("$.totalUsed").value(0.00))
                .andExpect(jsonPath("$.remainingAvailable").value(550.00));
    }

    @Test
    void additionalFundsFilteredByYearAndMonth() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"October","purpose":"Extra"}
                """);
        createAdditionalFundViaApi(user, """
                {"amount":100.00,"fundDate":"2026-11-03","sourceDescription":"November","purpose":"Extra"}
                """);

        mockMvc.perform(get("/api/v1/additional-funds?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds.length()").value(1))
                .andExpect(jsonPath("$.totalReceived").value(200.00));
    }

    @Test
    void createsAdditionalFund() throws Exception {
        User user = createUser(BigDecimal.ZERO);

        mockMvc.perform(post("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":250.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra income"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.fundDate").value("2026-10-05"))
                .andExpect(jsonPath("$.sourceDescription").value("Bonus"))
                .andExpect(jsonPath("$.purpose").value("Extra income"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void rejectsInvalidAdditionalFundRequest() throws Exception {
        User user = createUser(BigDecimal.ZERO);

        mockMvc.perform(post("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.00,\"fundDate\":\"2026-10-05\",\"sourceDescription\":\"X\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"fundDate\":\"2026-10-05\",\"sourceDescription\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesAdditionalFund() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long id = createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);

        mockMvc.perform(put("/api/v1/additional-funds/{id}", id)
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":300.00,"fundDate":"2026-10-05","sourceDescription":"Bonus Revised","purpose":"Revised"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(300.00))
                .andExpect(jsonPath("$.sourceDescription").value("Bonus Revised"))
                .andExpect(jsonPath("$.purpose").value("Revised"));
    }

    @Test
    void rejectsUpdateWhenAmountLessThanAllocated() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "150.00", "2026-10-15", true);
        financeService.replaceAllocations(user.getId(), expense.getId(),
                new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest(java.util.List.of(
                        new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest.AllocationItem(
                                FundingSource.ADDITIONAL, additionalId, new BigDecimal("150.00")))));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                financeService.updateAdditionalFund(user.getId(), additionalId,
                        new com.kodegladiators.kaasutracker.dto.AdditionalFundRequest(
                                new BigDecimal("100.00"), LocalDate.of(2026, 10, 5), "Bonus", "Extra")));
    }

    @Test
    void deactivatesAdditionalFund() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long id = createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);

        mockMvc.perform(delete("/api/v1/additional-funds/{id}", id)
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds.length()").value(1))
                .andExpect(jsonPath("$.funds[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.totalReceived").value(0.00))
                .andExpect(jsonPath("$.totalUsed").value(0.00))
                .andExpect(jsonPath("$.remainingAvailable").value(0.00));
    }

    @Test
    void rejectsDeactivationWhenFundHasAllocations() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "100.00", "2026-10-15", true);
        financeService.replaceAllocations(user.getId(), expense.getId(),
                new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest(java.util.List.of(
                        new com.kodegladiators.kaasutracker.dto.FundingAllocationRequest.AllocationItem(
                                FundingSource.ADDITIONAL, additionalId, new BigDecimal("100.00")))));

        mockMvc.perform(delete("/api/v1/additional-funds/{id}", additionalId)
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void preventsCrossUserAccess() throws Exception {
        User owner = createUser(BigDecimal.ZERO);
        User other = createUser(BigDecimal.ZERO);
        long id = createAdditionalFundViaApi(owner, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);

        mockMvc.perform(put("/api/v1/additional-funds/{id}", id)
                        .with(jwt().jwt(token -> token.subject(other.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":300.00,"fundDate":"2026-10-05","sourceDescription":"X","purpose":"Y"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/additional-funds/{id}", id)
                        .with(jwt().jwt(token -> token.subject(other.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/additional-funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"fundDate\":\"2026-10-05\",\"sourceDescription\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tracksAllocatedAmountAndAvailableBalance() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":500.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "600.00", "2026-10-15", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        MonthlyFinancialSummary summary = financeService.summary(user.getId(), 2026, 10);
        assertAmount("100.00", summary.salaryFundedExpenses());
        assertAmount("500.00", summary.additionalFundsUsed());
        assertAmount("600.00", summary.totalAllocatedExpenses());
        assertAmount("0.00", summary.remainingAdditionalFundsAvailable());
        assertAmount("0.00", summary.unfundedOverspending());

        mockMvc.perform(get("/api/v1/additional-funds?year=2026&month=10")
                        .with(jwt().jwt(token -> token.subject(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReceived").value(500.00))
                .andExpect(jsonPath("$.totalUsed").value(500.00))
                .andExpect(jsonPath("$.remainingAvailable").value(0.00));
    }

    @Test
    void rejectsAllocationExceedingAvailableBalance() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":100.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "200.00", "2026-10-15", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[{"fundingSource":"ADDITIONAL","sourceId":%d,"amount":200.00}]}
                                """.formatted(additionalId)))
                .andExpect(status().isConflict());
    }

    @Test
    void preventsDuplicateAdditionalFundAllocation() throws Exception {
        User user = createUser(BigDecimal.ZERO);
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":200.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "200.00", "2026-10-15", true);

        mockMvc.perform(put("/api/v1/expenses/{id}/funding", expense.getId())
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allocations":[
                                  {"fundingSource":"ADDITIONAL","sourceId":%d,"amount":100.00},
                                  {"fundingSource":"ADDITIONAL","sourceId":%d,"amount":100.00}
                                ]}
                                """.formatted(additionalId, additionalId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void additionalFundsReduceUnfundedOverspendingAndRollIntoCarryForward() throws Exception {
        User user = createUser(new BigDecimal("100.00"));
        long additionalId = createAdditionalFundViaApi(user, """
                {"amount":400.00,"fundDate":"2026-10-05","sourceDescription":"Bonus","purpose":"Extra"}
                """);
        Expense expense = createExpense(user, "400.00", "2026-10-15", true);
        financeService.recalculateMonthFunding(user.getId(), 2026, 10);

        MonthlyFinancialSummary october = financeService.summary(user.getId(), 2026, 10);
        assertAmount("100.00", october.salaryFundedExpenses());
        assertAmount("300.00", october.additionalFundsUsed());
        assertAmount("0.00", october.unfundedOverspending());
        assertAmount("100.00", october.remainingAdditionalFundsAvailable());
        assertAmount("100.00", october.totalAvailableFunding());

        financeService.establishCarryForward(user.getId(), 2026, 11);
        MonthlyFinancialSummary november = financeService.summary(user.getId(), 2026, 11);
        assertAmount("100.00", november.openingCarryForwardBalance());
        assertAmount("100.00", november.remainingCarryForwardAvailable());
    }

    private long createAdditionalFundViaApi(User user, String json) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/additional-funds")
                        .with(jwt().jwt(token -> token.subject(user.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .get("id").asLong();
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

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
