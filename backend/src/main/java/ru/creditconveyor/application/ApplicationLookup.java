package ru.creditconveyor.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Чтение заявки другими модулями, без проверки доступа текущего пользователя (фоновая обработка).
 */
@Service
@Transactional(readOnly = true)
public class ApplicationLookup {

    private final LoanApplicationRepository applications;

    ApplicationLookup(LoanApplicationRepository applications) {
        this.applications = applications;
    }

    public Optional<ApplicationStatus> statusOf(UUID applicationId) {
        return applications.findById(applicationId).map(LoanApplication::getStatus);
    }

    /** Нет заявки или не заполнены поля выбора продукта (до отправки) — пусто. */
    public Optional<ApplicationForDecision> forDecision(UUID applicationId) {
        return applications.findById(applicationId)
                .filter(application -> application.getProductCode() != null && application.getTerm() != null)
                .map(application -> new ApplicationForDecision(
                        application.getId(),
                        application.getCompanyId(),
                        application.getStatus(),
                        application.getProcessingRound(),
                        application.getProductCode(),
                        application.getRequestedAmount(),
                        application.getTerm(),
                        TermUnitDto.fromValue(application.getTermUnit()),
                        application.getRepaymentType() != null
                                ? RepaymentTypeDto.fromValue(application.getRepaymentType()) : null,
                        application.isSubscription(),
                        application.getAnnualRevenue(),
                        application.getAnnualExpenses(),
                        application.getBankMonthlyTurnover(),
                        application.getMonthlyDebtPayment()));
    }
}
