package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialInsightsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FinancialInsightsService.class);

    private final ExpenseRepository expenseRepository;
    private final ChatClient chatClient;

    public FinancialInsightsService(ExpenseRepository expenseRepository, ChatClient.Builder chatClientBuilder) {
        this.expenseRepository = expenseRepository;
        this.chatClient = chatClientBuilder.build();
    }

    public String generateInsights(String userId) {
        Map<String, BigDecimal> categoryTotals = fetchCurrentMonthCategoryTotals(userId);
        String prompt = buildPrompt(categoryTotals);

        try {
            return chatClient.prompt()
                    .system("You are a concise personal finance coach. Keep the answer to 2-3 sentences total, practical, and tailored to the user's actual spending mix. Use the user's local context and avoid generic filler.")
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception ex) {
            LOGGER.warn("Groq financial insight generation failed for user {}", userId, ex);
            return fallbackInsight(categoryTotals);
        }
    }

    @Transactional(readOnly = true)
    protected Map<String, BigDecimal> fetchCurrentMonthCategoryTotals(String userId) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate startDate = currentMonth.atDay(1);
        LocalDate endDate = currentMonth.atEndOfMonth();

        List<Expense> expenses = expenseRepository.findByUserIdAndExpenseDateBetween(userId, startDate, endDate);
        return aggregateByCategory(expenses);
    }

    private Map<String, BigDecimal> aggregateByCategory(List<Expense> expenses) {
        Map<String, BigDecimal> categoryTotals = new LinkedHashMap<>();

        for (Expense expense : expenses) {
            if (expense == null || expense.getCategory() == null || expense.getCategory().isBlank()) {
                continue;
            }

            BigDecimal netAmount = expense.getAmount() == null ? BigDecimal.ZERO : expense.getAmount();
            if (expense.getRefundedAmount() != null) {
                netAmount = netAmount.subtract(expense.getRefundedAmount());
            }
            if (netAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            String category = expense.getCategory().trim();
            categoryTotals.merge(category, netAmount, BigDecimal::add);
        }

        return categoryTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String buildPrompt(Map<String, BigDecimal> categoryTotals) {
        if (categoryTotals.isEmpty()) {
            return "The user has no recorded expenses for the current month. Provide a brief 2-3 sentence finance insight encouraging them to set a budget, track recurring costs, and plan a small emergency buffer. Keep it concise and actionable.";
        }

        BigDecimal totalSpend = categoryTotals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : categoryTotals.entrySet()) {
            BigDecimal share = totalSpend.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : entry.getValue().multiply(BigDecimal.valueOf(100)).divide(totalSpend, 1, RoundingMode.HALF_UP);
            entries.add(entry.getKey() + ": ₹" + entry.getValue().setScale(2, RoundingMode.HALF_UP) + " (" + share + "% of spending)");
        }

        String categorySummary = String.join(", ", entries);

        return "Analyze this user's monthly spending distribution and give a concise 2-3 sentence financial analysis with actionable advice. "
                + "Current month spending by category: " + categorySummary + ". "
                + "Focus on the biggest categories, identify any spending concentration or risk, and suggest 1-2 practical actions they can take immediately. "
                + "Keep the advice specific to this spending mix and avoid generic financial advice.";
    }

    private String fallbackInsight(Map<String, BigDecimal> categoryTotals) {
        if (categoryTotals.isEmpty()) {
            return "You have no recorded expenses this month. A good next step is to set a simple monthly budget and start tracking recurring categories like rent, groceries, and transport.";
        }

        Map.Entry<String, BigDecimal> topCategory = categoryTotals.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        if (topCategory == null) {
            return "Your spending is balanced this month. Consider setting a target budget for each category and reviewing recurring charges weekly.";
        }

        return "Your largest expense category is " + topCategory.getKey() + ", which suggests your spending is concentrated there. Consider reallocating a small part of that budget toward savings or reducing discretionary spend in the next month.";
    }
}
