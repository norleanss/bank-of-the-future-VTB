package ru.creditconveyor.application;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Моделируемая выдача: после подписания система перечисляет деньги ({@code SIGNED → DISBURSED}).
 */
@Component
class Disbursement {

    private final ApplicationStateMachine stateMachine;

    Disbursement(ApplicationStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @ApplicationModuleListener
    void on(ContractSigned event) {
        // Повторная доставка события после перезапуска — выдача уже была
        if (stateMachine.statusOf(event.applicationId()) == ApplicationStatus.SIGNED) {
            stateMachine.transition(event.applicationId(), ApplicationStatus.DISBURSED, Actor.system(),
                    "Деньги перечислены на счёт компании (моделирование)");
        }
    }
}
