package ru.creditconveyor.application;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Единственный вход для смены статуса из других модулей. Одобрение — только с условиями ({@link #offer}),
 * запрос сведений — только с запросом ({@link #requestInfo}), истечение — автоматически, ответ клиента на оффер —
 * через API заявок. Переданные причины заменяют причины прежнего решения. После изменения заявка сохраняется —
 * так публикуется событие {@link ApplicationStatusChanged}.
 */
@Service
@Transactional
public class ApplicationStateMachine {

    private static final Set<ApplicationStatus> CLIENT_OFFER_ANSWERS = EnumSet.of(
            ApplicationStatus.OFFER_ACCEPTED, ApplicationStatus.PENDING_CONFIRMATION, ApplicationStatus.OFFER_DECLINED);
    /** Отправка, подписание и начало проверки — только через API заявок и {@link #beginEnrichment}. */
    private static final Set<ApplicationStatus> OWN_PATH = EnumSet.of(
            ApplicationStatus.SUBMITTED, ApplicationStatus.ENRICHMENT, ApplicationStatus.SIGNED);
    private static final Set<ApplicationStatus> IN_PROCESSING = EnumSet.of(
            ApplicationStatus.SUBMITTED, ApplicationStatus.ENRICHMENT, ApplicationStatus.SCORING);

    public static final String PROCESSING_ERROR = "PROCESSING_ERROR";
    public static final String PRODUCT_CONDITIONS_CHANGED = "PRODUCT_CONDITIONS_CHANGED";

    private final LoanApplicationRepository applications;
    private final Clock clock;
    private final Duration offerValidity;

    ApplicationStateMachine(LoanApplicationRepository applications, Clock clock,
                            @Value("${app.offer.validity}") Duration offerValidity) {
        this.applications = applications;
        this.clock = clock;
        this.offerValidity = offerValidity;
    }

    /** Цель с отдельным путём (одобрение, истечение, ответ клиента) — {@code IllegalArgumentException}. */
    public void transition(UUID applicationId, ApplicationStatus to, Actor actor, @Nullable String comment) {
        transition(applicationId, to, actor, comment, null);
    }

    /** @param reasons заменяют причины прежнего решения; {@code null} — причины не меняются */
    public void transition(UUID applicationId, ApplicationStatus to, Actor actor, @Nullable String comment,
                           @Nullable List<DecisionReason> reasons) {
        if (LoanApplication.OFFERED.contains(to)) {
            throw new IllegalArgumentException("Одобрение и контрпредложение — через offer(...) с условиями");
        }
        if (to == ApplicationStatus.OFFER_EXPIRED) {
            throw new IllegalArgumentException("Предложение истекает автоматически");
        }
        if (to == ApplicationStatus.NEED_INFO) {
            throw new IllegalArgumentException("Запрос сведений — через requestInfo(...)");
        }
        if (OWN_PATH.contains(to)) {
            throw new IllegalArgumentException(to + " — только через API заявок или beginEnrichment(...)");
        }
        if (actor.type() == ActorType.CLIENT && CLIENT_OFFER_ANSWERS.contains(to)) {
            throw new IllegalArgumentException("Ответ клиента на предложение — через API заявок");
        }
        if (actor.type() == ActorType.CLIENT && to == ApplicationStatus.MANUAL_REVIEW) {
            throw new IllegalArgumentException("Ответ клиента на запрос банка — через API заявок");
        }
        LoanApplication application = load(applicationId);
        application.changeStatus(to, actor, comment, clock.instant());
        if (reasons != null) {
            application.replaceReasons(reasons);
        }
        applications.save(application);
    }

    public void offer(UUID applicationId, ApplicationStatus to, OfferTerms terms, Actor actor, @Nullable String comment) {
        offer(applicationId, to, terms, actor, comment, List.of());
    }

    public void offer(UUID applicationId, ApplicationStatus to, OfferTerms terms, Actor actor, @Nullable String comment,
                      List<DecisionReason> reasons) {
        LoanApplication application = load(applicationId);
        application.makeOffer(to, terms, actor, comment, clock.instant(), offerValidity);
        application.replaceReasons(reasons);
        applications.save(application);
    }

    /** {@code NEED_INFO} с запросом клиенту: какие поля анкеты дозаполнить. */
    public void requestInfo(UUID applicationId, String message, List<String> fields, Actor actor,
                            @Nullable String comment, List<DecisionReason> reasons) {
        requestInfo(applicationId, message, fields, List.of(), actor, comment, reasons);
    }

    /** @param documents типы документов ({@code DocumentType} контракта), которые нужно приложить к ответу */
    public void requestInfo(UUID applicationId, String message, List<String> fields, List<String> documents,
                            Actor actor, @Nullable String comment, List<DecisionReason> reasons) {
        LoanApplication application = load(applicationId);
        application.requestInfo(message, fields, documents, actor, comment, clock.instant());
        application.replaceReasons(reasons);
        applications.save(application);
    }

    /**
     * {@code SUBMITTED → ENRICHMENT}; после ответа на запрос заявка уже в {@code ENRICHMENT}.
     *
     * @param round круг обработки из события
     * @return {@code false}, если проверка уже не нужна: заявка в другом статусе или событие от прежнего круга
     *         (например, доставлено повторно после перезапуска)
     */
    public boolean beginEnrichment(UUID applicationId, int round) {
        LoanApplication application = load(applicationId);
        if (application.getProcessingRound() != round) {
            return false;
        }
        if (application.getStatus() == ApplicationStatus.SUBMITTED) {
            application.changeStatus(ApplicationStatus.ENRICHMENT, Actor.system(),
                    "Запрошены данные ЕГРЮЛ, ФНС и БКИ", clock.instant());
            applications.save(application);
        }
        return application.getStatus() == ApplicationStatus.ENRICHMENT;
    }

    /**
     * Непредвиденная ошибка автоматической обработки: заявка не должна зависнуть — она уходит на ручную проверку.
     * Подробности ошибки — только в логе, клиент видит общую причину.
     *
     * @param round круг обработки события, на котором случилась ошибка
     * @return {@code false}, если заявка уже не в обработке или ошибка — в событии другого круга
     */
    public boolean failProcessing(UUID applicationId, int round) {
        LoanApplication application = load(applicationId);
        if (application.getProcessingRound() != round || !IN_PROCESSING.contains(application.getStatus())) {
            return false;
        }
        String comment = "Автоматическая обработка не завершилась — заявку проверит специалист";
        if (application.getStatus() == ApplicationStatus.SUBMITTED) {
            application.changeStatus(ApplicationStatus.ENRICHMENT, Actor.system(), null, clock.instant());
        }
        application.changeStatus(ApplicationStatus.MANUAL_REVIEW, Actor.system(), comment, clock.instant());
        application.replaceReasons(List.of(new DecisionReason(PROCESSING_ERROR, comment)));
        applications.save(application);
        return true;
    }

    @Transactional(readOnly = true)
    public ApplicationStatus statusOf(UUID applicationId) {
        return load(applicationId).getStatus();
    }

    private LoanApplication load(UUID applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка " + applicationId + " не найдена"));
    }
}
