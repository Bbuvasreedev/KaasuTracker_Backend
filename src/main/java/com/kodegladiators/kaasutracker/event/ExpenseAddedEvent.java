package com.kodegladiators.kaasutracker.event;

import java.math.BigDecimal;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ExpenseAddedEvent extends ApplicationEvent {

    private final String userId;
    private final BigDecimal expenseAmount;

    public ExpenseAddedEvent(Object source, String userId, BigDecimal expenseAmount) {
        super(source);
        this.userId = userId;
        this.expenseAmount = expenseAmount;
    }
}