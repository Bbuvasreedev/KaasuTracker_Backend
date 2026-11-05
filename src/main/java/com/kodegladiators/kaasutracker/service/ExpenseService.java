package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.dto.ExpenseDto;
import com.kodegladiators.kaasutracker.dto.ExpenseResource;
import com.kodegladiators.kaasutracker.event.ExpenseAddedEvent;
import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import java.time.YearMonth;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ExpenseService {

    public record CreationResult(ExpenseResource resource, boolean created) {
    }

    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MonthlyFinanceService monthlyFinanceService;

    public ExpenseService(
            ExpenseRepository expenseRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            MonthlyFinanceService monthlyFinanceService
    ) {
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.monthlyFinanceService = monthlyFinanceService;
    }

    public List<ExpenseResource> findAllByUser(String userId) {
        requireUser(userId);
        return expenseRepository.findAllByUserIdOrderByExpenseDateDesc(userId)
                .stream()
                .map(ExpenseService::toResource)
                .toList();
    }

    public ExpenseResource findById(String userId, Long expenseId) {
        requireUser(userId);
        return toResource(findExpense(userId, expenseId));
    }

    @Transactional
    public CreationResult create(String userId, ExpenseDto request, String idempotencyKey) {
        User user = requireUserForUpdate(userId);
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.trim();
        if (normalizedKey != null) {
            if (normalizedKey.length() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key is too long");
            }
            Expense existing = expenseRepository.findByUserIdAndIdempotencyKey(userId, normalizedKey).orElse(null);
            if (existing != null) {
                if (!sameRequest(existing, request)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key was already used for a different expense request");
                }
                return new CreationResult(toResource(existing), false);
            }
        }
        Expense expense = new Expense();
        apply(expense, request);
        expense.setUser(user);
        expense.setIdempotencyKey(normalizedKey);
        Expense savedExpense = expenseRepository.save(expense);
        expenseRepository.flush();
        YearMonth period = YearMonth.from(savedExpense.getExpenseDate());
        monthlyFinanceService.recalculateMonthFunding(userId, period.getYear(), period.getMonthValue());
        publishExpenseAddedAfterCommit(userId, savedExpense);
        return new CreationResult(toResource(savedExpense), true);
    }

    @Transactional
    public ExpenseResource update(String userId, Long expenseId, ExpenseDto request) {
        requireUserForUpdate(userId);
        Expense expense = findExpense(userId, expenseId);
        if (expense.getEmiInstallment() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduled EMI payment expenses cannot be edited");
        }
        if (request.amount().compareTo(expense.getRefundedAmount()) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Expense amount cannot be less than the amount already refunded");
        }
        YearMonth previousPeriod = YearMonth.from(expense.getExpenseDate());
        boolean fundingInputsChanged = expense.getAmount().compareTo(request.amount()) != 0
                || !previousPeriod.equals(YearMonth.from(request.expenseDate()))
                || expense.isConfirmed() != request.isConfirmed();
        apply(expense, request);
        if (fundingInputsChanged) {
            expense.setFundingAllocationManual(false);
        }
        Expense savedExpense = expenseRepository.save(expense);
        expenseRepository.flush();
        YearMonth updatedPeriod = YearMonth.from(savedExpense.getExpenseDate());
        monthlyFinanceService.recalculateMonthFunding(userId, previousPeriod.getYear(), previousPeriod.getMonthValue());
        if (!updatedPeriod.equals(previousPeriod)) {
            monthlyFinanceService.recalculateMonthFunding(userId, updatedPeriod.getYear(), updatedPeriod.getMonthValue());
        }
        return toResource(savedExpense);
    }

    @Transactional
    public void delete(String userId, Long expenseId) {
        requireUserForUpdate(userId);
        Expense expense = findExpense(userId, expenseId);
        if (expense.getEmiInstallment() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduled EMI payment expenses cannot be deleted");
        }
        YearMonth period = YearMonth.from(expense.getExpenseDate());
        monthlyFinanceService.removeExpenseAllocations(userId, expenseId);
        expenseRepository.delete(expense);
        monthlyFinanceService.recalculateMonthFunding(userId, period.getYear(), period.getMonthValue());
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private User requireUserForUpdate(String userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private Expense findExpense(String userId, Long expenseId) {
        return expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
    }

    private void publishExpenseAddedAfterCommit(String userId, Expense expense) {
        ExpenseAddedEvent event = new ExpenseAddedEvent(this, userId, expense.getAmount());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private static void apply(Expense expense, ExpenseDto request) {
        expense.setAmount(request.amount());
        expense.setCategory(request.category().trim());
        expense.setMerchant(request.merchant());
        expense.setDescription(request.description());
        expense.setExpenseDate(request.expenseDate());
        expense.setReceiptUrl(request.receiptUrl());
        expense.setConfirmed(request.isConfirmed());
    }

    private static boolean sameRequest(Expense expense, ExpenseDto request) {
        return expense.getAmount().compareTo(request.amount()) == 0
                && expense.getCategory().equals(request.category().trim())
                && java.util.Objects.equals(expense.getMerchant(), request.merchant())
                && java.util.Objects.equals(expense.getDescription(), request.description())
                && expense.getExpenseDate().equals(request.expenseDate())
                && java.util.Objects.equals(expense.getReceiptUrl(), request.receiptUrl())
                && expense.isConfirmed() == request.isConfirmed();
    }

    private static ExpenseResource toResource(Expense expense) {
        return new ExpenseResource(
                expense.getId(),
                expense.getAmount(),
                expense.getRefundedAmount(),
                expense.getCategory(),
                expense.getMerchant(),
                expense.getDescription(),
                expense.getExpenseDate(),
                expense.getReceiptUrl(),
                expense.isConfirmed()
        );
    }
}