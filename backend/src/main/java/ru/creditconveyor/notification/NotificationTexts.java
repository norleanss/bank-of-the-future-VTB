package ru.creditconveyor.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.stream.Collectors;

import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.application.ActorType;
import ru.creditconveyor.application.ApplicationStatusChanged;
import ru.creditconveyor.application.DecisionReason;
import ru.creditconveyor.application.OfferWording;

/**
 * Какие переходы заслуживают уведомления и что в нём написать. О внутренних шагах конвейера ({@code ENRICHMENT},
 * {@code SCORING}) и о собственных действиях клиента (принял, отклонил, отменил, ответил на запрос) не уведомляем —
 * клиент и так видит результат на экране.
 */
final class NotificationTexts {

    record Text(String type, String title, String body) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private NotificationTexts() {
    }

    static Optional<Text> of(ApplicationStatusChanged event, ZoneId zone) {
        String number = event.number();
        ApplicationStatusChanged.Offer offer = event.offer();
        return Optional.ofNullable(switch (event.toStatus()) {
            case SUBMITTED -> new Text("APPLICATION_SUBMITTED", "Заявка отправлена",
                    "Заявка %s принята. Проверяем данные компании — решение появится в кабинете.".formatted(number));
            case NEED_INFO -> new Text("INFO_REQUESTED", "Банку нужны дополнительные сведения",
                    "По заявке %s: %s. Ответьте на запрос в карточке заявки.".formatted(number,
                            withoutFinalDot(event.infoRequestMessage() != null
                                    ? event.infoRequestMessage()
                                    : "дополните анкету")));
            case MANUAL_REVIEW -> event.actorType() == ActorType.CLIENT
                    ? null
                    : new Text("STATUS_CHANGED", "Заявка на рассмотрении у специалиста",
                    "Заявку %s проверит специалист банка. О решении сообщим здесь.".formatted(number));
            case APPROVED -> offer == null ? null : offer.preliminary()
                    ? new Text("DECISION_MADE", "Заявка предварительно одобрена",
                    "%s. Примите предложение до %s — после этого условия подтвердит специалист банка."
                            .formatted(approved(offer), date(offer, zone)))
                    : new Text("DECISION_MADE", "Заявка одобрена",
                    "%s. Примите предложение до %s.".formatted(approved(offer), date(offer, zone)));
            case COUNTER_OFFER -> offer == null ? null : new Text("DECISION_MADE", "Банк предлагает другие условия",
                    "По заявке %s доступно: %s («%s»). Ответьте на предложение до %s.".formatted(number,
                            conditions(offer), offer.productName(), date(offer, zone)));
            case REJECTED -> new Text("DECISION_MADE", "Заявка отклонена", event.reasons().isEmpty()
                    ? "По заявке %s банк не может выдать кредит.".formatted(number)
                    : "По заявке %s банк не может выдать кредит: %s.".formatted(number, event.reasons().stream()
                    .map(DecisionReason::message).map(NotificationTexts::withoutFinalDot)
                    .collect(Collectors.joining("; "))));
            case OFFER_ACCEPTED -> event.actorType() != ActorType.EMPLOYEE
                    ? null
                    : new Text("OFFER_CONFIRMED", "Условия подтверждены",
                    "Специалист банка подтвердил условия по заявке %s. Подпишите договор кодом подтверждения."
                            .formatted(number));
            case OFFER_EXPIRED -> new Text("OFFER_EXPIRED", "Срок предложения истёк", offer == null
                    ? "Предложение по заявке %s больше не действует. Вы можете подать новую заявку.".formatted(number)
                    : "Предложение по заявке %s действовало до %s. Вы можете подать новую заявку."
                    .formatted(number, date(offer, zone)));
            case SIGNED -> new Text("SIGNED", "Договор подписан",
                    "Договор по заявке %s подписан. Перечисляем деньги на счёт компании.".formatted(number));
            case DISBURSED -> offer != null && offer.termKind() == TermKindDto.TRANCHE
                    ? new Text("DISBURSED", "Лимит открыт", "Лимит %s по заявке %s открыт — берите транши до %s"
                    .formatted(OfferWording.rubles(offer.amount()), number, OfferWording.termUpTo(offer.term(), offer.termUnit())) + " на счёт компании.")
                    : new Text("DISBURSED", "Деньги перечислены", offer == null
                    ? "Деньги по заявке %s перечислены на счёт компании.".formatted(number)
                    : "%s по заявке %s перечислены на счёт компании.".formatted(OfferWording.rubles(offer.amount()),
                    number));
            case DRAFT, ENRICHMENT, SCORING, PENDING_CONFIRMATION, OFFER_DECLINED, CANCELLED -> null;
        });
    }

    private static String approved(ApplicationStatusChanged.Offer offer) {
        return OfferWording.approved(offer.preliminary(), offer.amount(), offer.term(), offer.termUnit(),
                offer.termKind());
    }

    private static String conditions(ApplicationStatusChanged.Offer offer) {
        return OfferWording.conditions(offer.amount(), offer.term(), offer.termUnit(), offer.termKind());
    }

    private static String date(ApplicationStatusChanged.Offer offer, ZoneId zone) {
        return DATE.format(offer.expiresAt().atZone(zone));
    }

    private static String withoutFinalDot(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
