package ru.creditconveyor.application;

import static ru.creditconveyor.application.ActorType.CLIENT;
import static ru.creditconveyor.application.ActorType.EMPLOYEE;
import static ru.creditconveyor.application.ActorType.SYSTEM;
import static ru.creditconveyor.application.ApplicationStatus.APPROVED;
import static ru.creditconveyor.application.ApplicationStatus.CANCELLED;
import static ru.creditconveyor.application.ApplicationStatus.COUNTER_OFFER;
import static ru.creditconveyor.application.ApplicationStatus.DISBURSED;
import static ru.creditconveyor.application.ApplicationStatus.DRAFT;
import static ru.creditconveyor.application.ApplicationStatus.ENRICHMENT;
import static ru.creditconveyor.application.ApplicationStatus.MANUAL_REVIEW;
import static ru.creditconveyor.application.ApplicationStatus.NEED_INFO;
import static ru.creditconveyor.application.ApplicationStatus.OFFER_ACCEPTED;
import static ru.creditconveyor.application.ApplicationStatus.OFFER_DECLINED;
import static ru.creditconveyor.application.ApplicationStatus.OFFER_EXPIRED;
import static ru.creditconveyor.application.ApplicationStatus.PENDING_CONFIRMATION;
import static ru.creditconveyor.application.ApplicationStatus.REJECTED;
import static ru.creditconveyor.application.ApplicationStatus.SCORING;
import static ru.creditconveyor.application.ApplicationStatus.SIGNED;
import static ru.creditconveyor.application.ApplicationStatus.SUBMITTED;

import java.util.HashSet;
import java.util.Set;

/**
 * Переходы статуса: из какого, в какой и кто. Всё, чего нет в таблице, — 409; какой из разрешённых
 * переходов выбрать, решает вызывающий код.
 */
final class StatusTransitions {

    record Transition(ApplicationStatus from, ApplicationStatus to, ActorType actor) {
    }

    static final Set<Transition> ALLOWED = build();

    private StatusTransitions() {
    }

    static boolean isAllowed(ApplicationStatus from, ApplicationStatus to, ActorType actor) {
        return ALLOWED.contains(new Transition(from, to, actor));
    }

    private static Set<Transition> build() {
        Set<Transition> allowed = new HashSet<>();
        // Клиент отправил заявку
        allow(allowed, CLIENT, DRAFT, SUBMITTED);
        // Автоматическая обработка: обогащение и скоринг
        allow(allowed, SYSTEM, SUBMITTED, ENRICHMENT);
        allow(allowed, SYSTEM, ENRICHMENT, SCORING, MANUAL_REVIEW, REJECTED);
        allow(allowed, SYSTEM, SCORING, REJECTED, NEED_INFO, MANUAL_REVIEW, COUNTER_OFFER, APPROVED);
        // Клиент ответил на запрос банка: запрос системы — снова на проверку, запрос андеррайтера — к нему
        allow(allowed, CLIENT, NEED_INFO, ENRICHMENT, MANUAL_REVIEW);
        // Андеррайтер разбирает заявку
        allow(allowed, EMPLOYEE, MANUAL_REVIEW, APPROVED, COUNTER_OFFER, NEED_INFO, REJECTED);
        // Клиент принимает предложение (сразу или как предварительное одобрение) или отказывается
        // ...или не отвечает 7 дней — предложение истекает
        for (ApplicationStatus offered : Set.of(APPROVED, COUNTER_OFFER)) {
            allow(allowed, CLIENT, offered, OFFER_ACCEPTED, PENDING_CONFIRMATION, OFFER_DECLINED);
            allow(allowed, SYSTEM, offered, OFFER_EXPIRED);
        }
        // Андеррайтер подтверждает крупное одобрение, меняет условия или отказывает
        allow(allowed, EMPLOYEE, PENDING_CONFIRMATION, OFFER_ACCEPTED, COUNTER_OFFER, REJECTED);
        // Подписание и моделируемая выдача
        allow(allowed, CLIENT, OFFER_ACCEPTED, SIGNED);
        allow(allowed, SYSTEM, SIGNED, DISBURSED);
        // Клиент отменяет заявку
        for (ApplicationStatus cancellable : Set.of(DRAFT, NEED_INFO, MANUAL_REVIEW)) {
            allow(allowed, CLIENT, cancellable, CANCELLED);
        }
        return Set.copyOf(allowed);
    }

    private static void allow(Set<Transition> allowed, ActorType actor, ApplicationStatus from, ApplicationStatus... to) {
        for (ApplicationStatus target : to) {
            allowed.add(new Transition(from, target, actor));
        }
    }
}
