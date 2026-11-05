package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiInstallment;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentRepository;
import com.kodegladiators.kaasutracker.repository.EmiRepository;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmiInstallmentProcessor {

    private final EmiRepository emiRepository;
    private final EmiInstallmentRepository installmentRepository;
    private final ExpenseRepository expenseRepository;
    private final UserRepository userRepository;
    private final MonthlyFinanceService financeService;
    private final EmiService emiService;

    public EmiInstallmentProcessor(
            EmiRepository emiRepository,
            EmiInstallmentRepository installmentRepository,
            ExpenseRepository expenseRepository,
            UserRepository userRepository,
            MonthlyFinanceService financeService,
            EmiService emiService
    ) {
        this.emiRepository = emiRepository;
        this.installmentRepository = installmentRepository;
        this.expenseRepository = expenseRepository;
        this.userRepository = userRepository;
        this.financeService = financeService;
        this.emiService = emiService;
    }

    @Transactional
    public boolean process(Long emiId, LocalDate dueDate, LocalDate processingDate) {
        if (dueDate.isAfter(processingDate)) {
            return false;
        }
        Emi initial = emiRepository.findById(emiId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EMI not found"));
        User user = userRepository.findByIdForUpdate(initial.getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        Emi emi = emiRepository.findByIdForUpdate(emiId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EMI not found"));
        if (emi.getStatus() != EmiStatus.ACTIVE || !EmiSchedule.isScheduledDate(emi, dueDate)) {
            return false;
        }

        EmiInstallment installment = installmentRepository.findByEmiIdAndDueDate(emiId, dueDate)
                .orElseGet(() -> {
                    EmiInstallment newInstallment = new EmiInstallment();
                    newInstallment.setEmi(emi);
                    newInstallment.setDueDate(dueDate);
                    newInstallment.setAmount(emi.getAmount());
                    return installmentRepository.saveAndFlush(newInstallment);
                });
        if (installment.getStatus() == EmiInstallmentStatus.PAID) {
            return false;
        }

        Expense expense = expenseRepository.findByEmiInstallmentId(installment.getId()).orElse(null);
        if (expense == null) {
            expense = new Expense();
            expense.setUser(user);
            expense.setAmount(installment.getAmount());
            expense.setCategory("EMI");
            expense.setMerchant(emi.getTitle());
            expense.setDescription("EMI installment due " + dueDate);
            expense.setExpenseDate(dueDate);
            expense.setConfirmed(true);
            expense.setEmiInstallment(installment);
            expense = expenseRepository.saveAndFlush(expense);
        }

        YearMonth period = YearMonth.from(dueDate);
        financeService.recalculateMonthFunding(user.getId(), period.getYear(), period.getMonthValue());
        installment.setStatus(EmiInstallmentStatus.PAID);
        installmentRepository.saveAndFlush(installment);
        long paid = installmentRepository.countByEmiIdAndStatus(emiId, EmiInstallmentStatus.PAID);
        emiService.completeIfFullyPaid(emi, paid);
        return true;
    }
}
