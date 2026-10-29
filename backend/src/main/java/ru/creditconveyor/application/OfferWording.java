package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Условия предложения словами — для подсказки клиенту и уведомлений. У кредита одной суммой срок — срок возврата
 * («500 000 ₽ на 24 месяца»), у линий, овердрафта и Гамма-Старта — срок одного транша («лимит 800 000 ₽, транши
 * до 60 дней»): иначе похоже, что всю сумму надо вернуть за этот срок.
 */
public final class OfferWording {

    private OfferWording() {
    }

    /** «500 000 ₽ на 24 месяца» или «лимит 800 000 ₽, транши до 60 дней» */
    public static String conditions(BigDecimal amount, int term, TermUnitDto unit, TermKindDto kind) {
        return kind == TermKindDto.TRANCHE
                ? "лимит " + rubles(amount) + ", транши до " + termUpTo(term, unit)
                : rubles(amount) + " на " + term(term, unit);
    }

    /** «Одобрено 500 000 ₽ на 24 месяца», «Предварительно одобрен лимит 800 000 ₽, транши до 60 дней» */
    public static String approved(boolean preliminary, BigDecimal amount, int term, TermUnitDto unit, TermKindDto kind) {
        String verb = kind == TermKindDto.TRANCHE ? "одобрен" : "одобрено";
        String phrase = verb + " " + conditions(amount, term, unit, kind);
        return preliminary ? "Предварительно " + phrase : Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1);
    }

    /** 1 500 000 ₽, 26 663,5 ₽ */
    public static String rubles(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + " ₽";
    }

    /** После «на»: 1 месяц, 3 месяца, 60 дней */
    static String term(int count, TermUnitDto unit) {
        return count + " " + (unit == TermUnitDto.MONTHS
                ? plural(count, "месяц", "месяца", "месяцев")
                : plural(count, "день", "дня", "дней"));
    }

    /** После «до»: до 1 месяца, до 6 месяцев, до 21 дня, до 60 дней */
    public static String termUpTo(int count, TermUnitDto unit) {
        boolean one = count % 10 == 1 && count % 100 != 11;
        return count + " " + (unit == TermUnitDto.MONTHS
                ? (one ? "месяца" : "месяцев")
                : (one ? "дня" : "дней"));
    }

    private static String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        return mod10 >= 2 && mod10 <= 4 ? few : many;
    }
}
