package ru.creditconveyor.scoring;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Балл 0–100, грейд и разбивка: сумма {@code points} всех факторов равна {@code score}.
 */
record ScoreResult(int score, String grade, List<FactorResult> factors) {

    /**
     * @param inputValue значение, по которому начислены баллы; нет, если входных данных нет
     * @param maxPoints  максимум фактора (у нормирования — 0)
     */
    record FactorResult(Factor factor, @Nullable String inputValue, int points, int maxPoints, String explanation) {
    }
}
