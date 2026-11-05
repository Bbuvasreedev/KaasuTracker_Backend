package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import com.kodegladiators.kaasutracker.repository.EmiRepository;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentRepository;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class EmiSchedulerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmiSchedulerService.class);
    private static final int REMINDER_DAYS = 3;

    private final EmiRepository emiRepository;
    private final EmiInstallmentRepository installmentRepository;
    private final EmiInstallmentProcessor installmentProcessor;
    private final Clock clock;

    public EmiSchedulerService(
            EmiRepository emiRepository,
            EmiInstallmentRepository installmentRepository,
            EmiInstallmentProcessor installmentProcessor,
            Clock clock
    ) {
        this.emiRepository = emiRepository;
        this.installmentRepository = installmentRepository;
        this.installmentProcessor = installmentProcessor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void processDueEmis() {
        long startedAt = System.nanoTime();
        LocalDate today = LocalDate.now(clock);
        int attemptedInstallments = 0;
        try {
            for (Emi emi : emiRepository.findAllByStatusOrderByIdAsc(EmiStatus.ACTIVE)) {
                attemptedInstallments += processDueInstallments(emi, today);
            }
        } finally {
            LOGGER.info(
                    "Scheduled EMI scan completed in {} ms; installment attempts={}",
                    (System.nanoTime() - startedAt) / 1_000_000,
                    attemptedInstallments
            );
        }
    }

    public void processDueInstallmentsForUser(String userId, LocalDate today) {
        long startedAt = System.nanoTime();
        int attemptedInstallments = 0;
        try {
            for (Emi emi : emiRepository.findAllByUserIdAndStatusOrderByIdAsc(userId, EmiStatus.ACTIVE)) {
                attemptedInstallments += processDueInstallments(emi, today);
            }
        } finally {
            LOGGER.debug(
                    "User EMI scan completed in {} ms; installment attempts={}",
                    (System.nanoTime() - startedAt) / 1_000_000,
                    attemptedInstallments
            );
        }
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void sendUpcomingEmiReminders() {
        LocalDate reminderDate = LocalDate.now(clock).plusDays(REMINDER_DAYS);
        int reminders = 0;
        for (Emi emi : emiRepository.findAllByStatusOrderByIdAsc(EmiStatus.ACTIVE)) {
            if (EmiSchedule.installmentDatesBetween(emi, reminderDate, reminderDate).contains(reminderDate)) {
                reminders++;
            }
        }
        LOGGER.info("Upcoming EMI reminder scan completed; due installments={}", reminders);
    }

    private int processDueInstallments(Emi emi, LocalDate today) {
        List<LocalDate> dueDates = EmiSchedule.installmentDatesThrough(emi, today);
        if (dueDates.isEmpty() || dueDates.get(0).isAfter(today)) {
            return 0;
        }
        Set<LocalDate> paidDates = new HashSet<>();
        for (EmiInstallmentSnapshot snapshot : installmentRepository.findSnapshotsByEmiAndDueDateBetween(
                emi.getId(), dueDates.get(0), today)) {
            if (snapshot.status() == EmiInstallmentStatus.PAID) {
                paidDates.add(snapshot.dueDate());
            }
        }

        int attemptedInstallments = 0;
        for (LocalDate dueDate : dueDates) {
            if (dueDate.isAfter(today)) {
                break;
            }
            if (paidDates.contains(dueDate)) {
                continue;
            }
            attemptedInstallments++;
            try {
                installmentProcessor.process(emi.getId(), dueDate, today);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Failed to process a due EMI installment. Its transaction was rolled back.",
                        exception
                );
            }
        }
        return attemptedInstallments;
    }
}
