package com.kodegladiators.kaasutracker.listener;

import com.kodegladiators.kaasutracker.event.ExpenseAddedEvent;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ThresholdAlertListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThresholdAlertListener.class);
    private static final BigDecimal PERCENTAGE_DIVISOR = BigDecimal.valueOf(100);

    private final UserRepository userRepository;
    private final ExpenseRepository expenseRepository;

    public ThresholdAlertListener(UserRepository userRepository, ExpenseRepository expenseRepository) {
        this.userRepository = userRepository;
        this.expenseRepository = expenseRepository;
    }

    @Async
    @EventListener
    public void handleExpenseAdded(ExpenseAddedEvent event) {
        User user = userRepository.findById(event.getUserId()).orElse(null);
        if (user == null) {
            LOGGER.warn("Skipping expense threshold check: user {} no longer exists", event.getUserId());
            return;
        }

        BigDecimal salary = user.getBaseSalary();
        BigDecimal thresholdPercentage = user.getThresholdPercentage();
        if (salary == null || thresholdPercentage == null) {
            LOGGER.warn(
                    "Skipping expense threshold check for user {} because salary or threshold is not configured",
                    event.getUserId()
            );
            return;
        }

        YearMonth currentMonth = YearMonth.now();
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();
        BigDecimal monthlyExpenses = expenseRepository.sumAmountByUserIdAndExpenseDateBetween(
                event.getUserId(),
                monthStart,
                monthEnd
        );
        BigDecimal thresholdLimit = salary.multiply(thresholdPercentage).divide(PERCENTAGE_DIVISOR);

        if (monthlyExpenses.compareTo(thresholdLimit) > 0) {
            LOGGER.warn(
                    "Expense threshold exceeded for user {}: monthly expenses {} exceed the configured limit {} "
                            + "after an expense of {}",
                    event.getUserId(),
                    monthlyExpenses,
                    thresholdLimit,
                    event.getExpenseAmount()
            );
        }
    }
}
