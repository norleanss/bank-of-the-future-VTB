package ru.creditconveyor.application;

import static ru.creditconveyor.api.ApplicationActionDto.ACCEPT_OFFER;
import static ru.creditconveyor.api.ApplicationActionDto.CANCEL;
import static ru.creditconveyor.api.ApplicationActionDto.DECLINE_OFFER;
import static ru.creditconveyor.api.ApplicationActionDto.EDIT;
import static ru.creditconveyor.api.ApplicationActionDto.RESPOND_INFO;
import static ru.creditconveyor.api.ApplicationActionDto.SIGN;
import static ru.creditconveyor.api.ApplicationActionDto.SUBMIT;
import static ru.creditconveyor.api.ApplicationActionDto.UPLOAD_DOCUMENT;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import ru.creditconveyor.api.ApplicationActionDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Кнопки ({@code availableActions}) и подсказка клиенту вычисляются из статуса, фронтенд их не дублирует.
 */
final class ClientView {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ClientView() {
    }

    static List<ApplicationActionDto> availableActions(ApplicationStatus status) {
        return switch (status) {
            case DRAFT -> List.of(EDIT, SUBMIT, CANCEL);
            case NEED_INFO -> List.of(RESPOND_INFO, UPLOAD_DOCUMENT, CANCEL);
            case MANUAL_REVIEW -> List.of(CANCEL);
            case APPROVED, COUNTER_OFFER -> List.of(ACCEPT_OFFER, DECLINE_OFFER);
            case OFFER_ACCEPTED -> List.of(SIGN);
            case SUBMITTED, ENRICHMENT, SCORING, PENDING_CONFIRMATION, SIGNED,
                 DISBURSED, REJECTED, OFFER_DECLINED, OFFER_EXPIRED, CANCELLED -> List.of();
        };
    }

    /**
     * Подсказка с условиями предложения: сумма и срок (у линий и овердрафта — лимит и срок транша) и до какого
     * числа ответить; у предварительного одобрения — что условия ещё подтвердит специалист.
     */
    static String nextStepHint(ApplicationStatus status, LoanOffer offer, InfoRequest openRequest, ZoneId zone) {
        return switch (status) {
            case DRAFT -> "Заполните анкету и отправьте заявку";
            case SUBMITTED -> "Заявка отправлена — начинаем проверку";
            case ENRICHMENT -> "Проверяем данные компании в ЕГРЮЛ, ФНС и БКИ";
            case SCORING -> "Оцениваем заявку — решение появится через несколько секунд";
            case NEED_INFO -> needInfo(openRequest);
            case MANUAL_REVIEW -> "Заявку проверяет специалист банка. О решении сообщим в уведомлениях";
            case APPROVED -> offer == null
                    ? "Ознакомьтесь с условиями и примите предложение"
                    : OfferWording.approved(offer.isPreliminary(), offer.getAmount(), offer.getTerm(),
                    TermUnitDto.fromValue(offer.getTermUnit()), TermKindDto.fromValue(offer.getTermKind()))
                    + ". Примите предложение до " + date(offer, zone)
                    + (offer.isPreliminary() ? " — после этого условия подтвердит специалист банка" : "");
            case COUNTER_OFFER -> offer == null
                    ? "Банк предлагает другие условия — примите их или откажитесь"
                    : "Банк предлагает другие условия: " + conditions(offer) + ". Примите их до " + date(offer, zone)
                    + " или откажитесь"
                    + (offer.isPreliminary() ? ". После вашего согласия условия подтвердит специалист банка" : "");
            case PENDING_CONFIRMATION -> "Вы приняли предложение — специалист банка подтверждает условия. "
                    + "После подтверждения можно будет подписать договор";
            case OFFER_ACCEPTED -> "Подпишите договор: запросите код подтверждения и введите его";
            case SIGNED -> "Договор подписан — перечисляем деньги на счёт компании";
            case DISBURSED -> offer != null && TermKindDto.fromValue(offer.getTermKind()) == TermKindDto.TRANCHE
                    ? "Лимит открыт — берите транши на счёт компании по мере необходимости"
                    : "Деньги перечислены на счёт компании";
            case REJECTED -> "К сожалению, банк не может выдать кредит по этой заявке. Причины и что можно сделать — "
                    + "в карточке заявки";
            case OFFER_DECLINED -> "Вы отказались от предложения. Можно подать новую заявку";
            case OFFER_EXPIRED -> "Срок действия предложения истёк. Вы можете подать новую заявку";
            case CANCELLED -> "Заявка отменена. Можно подать новую заявку";
        };
    }

    /** «Банку нужны дополнительные сведения — дополните анкету, приложите документы и ответьте на запрос» */
    private static String needInfo(InfoRequest request) {
        List<String> steps = new ArrayList<>();
        if (request != null && !request.getRequestedFields().isEmpty()) {
            steps.add("дополните анкету");
        }
        if (request != null && !request.getRequestedDocuments().isEmpty()) {
            steps.add("приложите документы");
        }
        steps.add("ответьте на запрос");
        String last = steps.removeLast();
        return "Банку нужны дополнительные сведения — "
                + (steps.isEmpty() ? last : String.join(", ", steps) + " и " + last);
    }

    private static String conditions(LoanOffer offer) {
        return OfferWording.conditions(offer.getAmount(), offer.getTerm(), TermUnitDto.fromValue(offer.getTermUnit()),
                TermKindDto.fromValue(offer.getTermKind()));
    }

    private static String date(LoanOffer offer, ZoneId zone) {
        return DATE.format(offer.getExpiresAt().atZone(zone));
    }
}
