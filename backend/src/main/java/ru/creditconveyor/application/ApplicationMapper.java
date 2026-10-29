package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import ru.creditconveyor.api.ActorTypeDto;
import ru.creditconveyor.api.ApplicationCreateRequestDto;
import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.ApplicationPatchRequestDto;
import ru.creditconveyor.api.ApplicationPatchRequestFinancialsDto;
import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.ApplicationSummaryDto;
import ru.creditconveyor.api.DocumentTypeDto;
import ru.creditconveyor.api.FinancialsDto;
import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.InfoRequestDto;
import ru.creditconveyor.api.OfferDto;
import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.api.PurposeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.ReasonDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.StatusHistoryItemDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Заявка ↔ DTO контракта. Поля без значения остаются {@code null} и в ответ не попадают (Jackson {@code NON_NULL}).
 */
final class ApplicationMapper {

    private static final Set<ApplicationStatus> OFFER_VISIBLE = EnumSet.of(
            ApplicationStatus.APPROVED, ApplicationStatus.COUNTER_OFFER, ApplicationStatus.PENDING_CONFIRMATION,
            ApplicationStatus.OFFER_ACCEPTED, ApplicationStatus.SIGNED, ApplicationStatus.DISBURSED,
            ApplicationStatus.OFFER_EXPIRED);

    private ApplicationMapper() {
    }

    static ApplicationDto toDto(LoanApplication application, ZoneId zone) {
        LoanOffer offer = OFFER_VISIBLE.contains(application.getStatus())
                ? application.currentOffer().orElse(null)
                : null;
        return new ApplicationDto(
                        application.getId(),
                        application.getNumber(),
                        toDto(application.getStatus()),
                        application.getCompanyId(),
                        application.getReasons().stream()
                                .map(reason -> new ReasonDto(reason.code(), reason.message()).advice(reason.advice()))
                                .toList(),
                        ClientView.availableActions(application.getStatus()),
                        at(application.getCreatedAt(), zone),
                        at(application.getUpdatedAt(), zone))
                .productCode(map(application.getProductCode(), ProductCodeDto::fromValue))
                .purpose(map(application.getPurpose(), PurposeDto::fromValue))
                .requestedAmount(money(application.getRequestedAmount()))
                .term(application.getTerm())
                .termUnit(map(application.getTermUnit(), TermUnitDto::fromValue))
                .fundingMode(map(application.getFundingMode(), FundingModeDto::fromValue))
                .repaymentType(map(application.getRepaymentType(), RepaymentTypeDto::fromValue))
                .financials(financials(application))
                .collateral(application.isCollateral())
                .subscription(application.isSubscription())
                .offer(offer != null ? toDto(offer, zone) : null)
                .openInfoRequest(application.getStatus() == ApplicationStatus.NEED_INFO
                        ? application.openInfoRequest().map(request -> toDto(request, zone)).orElse(null)
                        : null)
                .nextStepHint(ClientView.nextStepHint(application.getStatus(), offer,
                        application.openInfoRequest().orElse(null), zone))
                .submittedAt(application.getSubmittedAt() != null ? at(application.getSubmittedAt(), zone) : null);
    }

    static ApplicationSummaryDto toSummaryDto(LoanApplication application, String companyName, int unreadNotifications,
                                              ZoneId zone) {
        return new ApplicationSummaryDto(
                        application.getId(),
                        application.getNumber(),
                        toDto(application.getStatus()),
                        application.getCompanyId(),
                        companyName,
                        at(application.getCreatedAt(), zone),
                        at(application.getUpdatedAt(), zone))
                .purpose(map(application.getPurpose(), PurposeDto::fromValue))
                .productCode(map(application.getProductCode(), ProductCodeDto::fromValue))
                .requestedAmount(money(application.getRequestedAmount()))
                .unreadNotifications(unreadNotifications);
    }

    static StatusHistoryItemDto toDto(StatusHistoryEntry entry, ZoneId zone) {
        return new StatusHistoryItemDto(
                        toDto(entry.getToStatus()),
                        ActorTypeDto.fromValue(entry.getActorType().name()),
                        at(entry.getCreatedAt(), zone))
                .fromStatus(entry.getFromStatus() != null ? toDto(entry.getFromStatus()) : null)
                .comment(entry.getComment());
    }

    static DraftChanges toChanges(ApplicationCreateRequestDto request) {
        FinancialsDto financials = request.getFinancials() != null ? request.getFinancials() : new FinancialsDto();
        return new DraftChanges(
                FieldChange.ofNullable(map(request.getProductCode(), ProductCodeDto::getValue)),
                FieldChange.ofNullable(map(request.getPurpose(), PurposeDto::getValue)),
                FieldChange.ofNullable(parseMoney(request.getRequestedAmount())),
                FieldChange.ofNullable(request.getTerm()),
                FieldChange.ofNullable(map(request.getTermUnit(), TermUnitDto::getValue)),
                FieldChange.ofNullable(map(request.getFundingMode(), FundingModeDto::getValue)),
                FieldChange.ofNullable(map(request.getRepaymentType(), RepaymentTypeDto::getValue)),
                FieldChange.ofNullable(parseMoney(financials.getAnnualRevenue())),
                FieldChange.ofNullable(parseMoney(financials.getAnnualExpenses())),
                FieldChange.ofNullable(parseMoney(financials.getBankMonthlyTurnover())),
                FieldChange.ofNullable(parseMoney(financials.getMonthlyDebtPayment())),
                FieldChange.ofNullable(request.getCollateral()),
                FieldChange.ofNullable(request.getSubscription()));
    }

    /** Наличие поля и {@code null} — по дереву JSON {@code document}, значения — из DTO. */
    static DraftChanges toChanges(ApplicationPatchRequestDto request, JsonNode document) {
        ApplicationPatchRequestFinancialsDto financials = request.getFinancials() != null
                ? request.getFinancials()
                : new ApplicationPatchRequestFinancialsDto();
        JsonNode financialsNode = document != null ? document.get("financials") : null;
        boolean clearFinancials = financialsNode != null && financialsNode.isNull();
        return new DraftChanges(
                change(document, "productCode", map(request.getProductCode(), value -> value.getValue())),
                change(document, "purpose", map(request.getPurpose(), value -> value.getValue())),
                change(document, "requestedAmount", parseMoney(request.getRequestedAmount())),
                change(document, "term", request.getTerm()),
                change(document, "termUnit", map(request.getTermUnit(), value -> value.getValue())),
                change(document, "fundingMode", map(request.getFundingMode(), value -> value.getValue())),
                change(document, "repaymentType", map(request.getRepaymentType(), value -> value.getValue())),
                financialChange(financialsNode, clearFinancials, "annualRevenue", parseMoney(financials.getAnnualRevenue())),
                financialChange(financialsNode, clearFinancials, "annualExpenses", parseMoney(financials.getAnnualExpenses())),
                financialChange(financialsNode, clearFinancials, "bankMonthlyTurnover", parseMoney(financials.getBankMonthlyTurnover())),
                financialChange(financialsNode, clearFinancials, "monthlyDebtPayment", parseMoney(financials.getMonthlyDebtPayment())),
                change(document, "collateral", request.getCollateral()),
                change(document, "subscription", request.getSubscription()));
    }

    private static <T> FieldChange<T> change(JsonNode object, String field, T value) {
        JsonNode node = object != null ? object.get(field) : null;
        if (node == null) {
            return FieldChange.absent();
        }
        return node.isNull() ? FieldChange.clear() : FieldChange.set(value);
    }

    private static <T> FieldChange<T> financialChange(JsonNode financials, boolean clearAll, String field, T value) {
        if (clearAll) {
            return FieldChange.clear();
        }
        return financials != null && financials.isObject() ? change(financials, field, value) : FieldChange.absent();
    }

    private static OfferDto toDto(LoanOffer offer, ZoneId zone) {
        return new OfferDto(
                        ProductCodeDto.fromValue(offer.getProductCode()),
                        offer.getProductName(),
                        money(offer.getAmount()),
                        offer.getTerm(),
                        TermUnitDto.fromValue(offer.getTermUnit()),
                        TermKindDto.fromValue(offer.getTermKind()),
                        offer.getRate(),
                        RateUnitDto.fromValue(offer.getRateUnit()),
                        offer.isSubscription(),
                        offer.isPreliminary(),
                        at(offer.getExpiresAt(), zone))
                .agreementTermMonths(offer.getAgreementTermMonths())
                .monthlyPayment(money(offer.getMonthlyPayment()))
                .subscriptionFee(money(offer.getSubscriptionFee()));
    }

    static InfoRequestDto toDto(InfoRequest request, ZoneId zone) {
        return new InfoRequestDto(
                        request.getId(),
                        request.getMessage(),
                        request.getRequestedFields(),
                        request.getRequestedDocuments().stream().map(DocumentTypeDto::fromValue).toList(),
                        InfoRequestDto.CreatedByEnum.fromValue(request.getCreatedBy()),
                        at(request.getCreatedAt(), zone),
                        InfoRequestDto.StatusEnum.fromValue(request.getStatus()))
                .response(request.getResponse());
    }

    private static FinancialsDto financials(LoanApplication application) {
        if (application.getAnnualRevenue() == null && application.getAnnualExpenses() == null
                && application.getBankMonthlyTurnover() == null && application.getMonthlyDebtPayment() == null) {
            return null;
        }
        return new FinancialsDto()
                .annualRevenue(money(application.getAnnualRevenue()))
                .annualExpenses(money(application.getAnnualExpenses()))
                .bankMonthlyTurnover(money(application.getBankMonthlyTurnover()))
                .monthlyDebtPayment(money(application.getMonthlyDebtPayment()));
    }

    private static ApplicationStatusDto toDto(ApplicationStatus status) {
        return ApplicationStatusDto.fromValue(status.name());
    }

    private static <S, T> T map(S value, Function<S, T> mapper) {
        return value != null ? mapper.apply(value) : null;
    }

    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return OffsetDateTime.ofInstant(instant, zone);
    }

    private static String money(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : null;
    }

    private static BigDecimal parseMoney(String amount) {
        return amount != null ? new BigDecimal(amount) : null;
    }
}
