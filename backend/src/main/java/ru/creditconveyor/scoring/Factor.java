package ru.creditconveyor.scoring;

import org.jspecify.annotations.Nullable;

/**
 * Факторы скоринга. {@code weakReason} — причина отказа по баллу, если фактор набрал мало, с советом клиенту.
 */
enum Factor {
    PROFITABILITY(true, new Reason("LOW_PROFITABILITY", "Низкая рентабельность бизнеса",
            "Проверьте, что в анкете указаны все доходы бизнеса за год")),
    DEBT_LOAD(true, new Reason("DEBT_LOAD_HIGH", "Высокая долговая нагрузка",
            "Погасите часть действующих кредитов или запросите меньшую сумму")),
    OVERDUE_DAYS(false, new Reason("OVERDUE_HISTORY", "Были просрочки по кредитам",
            "Платите по текущим кредитам без просрочек — кредитная история улучшится через несколько месяцев")),
    TAX_DEBT(false, new Reason("TAX_DEBT", "Есть налоговая задолженность",
            "Погасите налоговую задолженность")),
    BUSINESS_AGE(false, new Reason("YOUNG_BUSINESS", "Небольшой срок работы бизнеса",
            "Новому бизнесу подойдёт Гамма-Старт — без финансовых документов")),
    ACTIVE_CREDITS(false, new Reason("MANY_ACTIVE_CREDITS", "Много действующих кредитов",
            "Закройте часть действующих кредитов или объедините их рефинансированием")),
    /** Поправка до шкалы 0–100, когда финансовые факторы не считались */
    NORMALIZATION(false, null);

    private final boolean financial;
    private final @Nullable Reason weakReason;

    Factor(boolean financial, @Nullable Reason weakReason) {
        this.financial = financial;
        this.weakReason = weakReason;
    }

    boolean financial() {
        return financial;
    }

    @Nullable Reason weakReason() {
        return weakReason;
    }
}
