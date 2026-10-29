package ru.creditconveyor.scoring;

import org.jspecify.annotations.Nullable;

/**
 * Причина решения: код (справочник {@code reasonCodes}), текст для клиента и совет, что делать дальше (у причин
 * отказа и контрпредложения; тексты — docs/scoring.md).
 */
record Reason(String code, String message, @Nullable String advice) {

    Reason(String code, String message) {
        this(code, message, null);
    }

    static final String COMPANY_NOT_FOUND = "COMPANY_NOT_FOUND";
    static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
    static final String COMPANY_LIQUIDATING = "COMPANY_LIQUIDATING";
    static final String COMPANY_LIQUIDATED = "COMPANY_LIQUIDATED";
    static final String OVERDUE_GT_60 = "OVERDUE_GT_60";
    static final String TAX_DEBT_HIGH = "TAX_DEBT_HIGH";
    static final String PRODUCT_NOT_ELIGIBLE = "PRODUCT_NOT_ELIGIBLE";
    static final String MISSING_FIELDS = "MISSING_FIELDS";
    static final String SCORE_LOW = "SCORE_LOW";
    static final String SCORE_GREY_ZONE = "SCORE_GREY_ZONE";
    static final String OVERDUE_31_60 = "OVERDUE_31_60";
    static final String LOSS_MAKING = "LOSS_MAKING";
    static final String AMOUNT_ABOVE_LIMIT = "AMOUNT_ABOVE_LIMIT";
    static final String INSUFFICIENT_FINANCES = "INSUFFICIENT_FINANCES";
}
