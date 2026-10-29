package ru.creditconveyor.application;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class StoredReason {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String message;

    private String advice;

    protected StoredReason() {
    }

    StoredReason(DecisionReason reason) {
        this.code = reason.code();
        this.message = reason.message();
        this.advice = reason.advice();
    }

    DecisionReason toReason() {
        return new DecisionReason(code, message, advice);
    }
}
