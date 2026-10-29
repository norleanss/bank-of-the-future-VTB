package ru.creditconveyor.product;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.DictionariesApi;
import ru.creditconveyor.api.DictionariesDto;
import ru.creditconveyor.api.DictionaryItemDto;
import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.PurposeDto;
import ru.creditconveyor.api.RepaymentTypeDto;

/**
 * Русские подписи кодов. Справочники общие для всех модулей и живут здесь, пока их немного.
 */
@RestController
class DictionariesController implements DictionariesApi {

    static final DictionariesDto DICTIONARIES = new DictionariesDto()
            .purposes(List.of(
                    item(PurposeDto.WORKING_CAPITAL, "Пополнение оборотных средств",
                            "Регулярные расходы: закупки, зарплата, налоги, сезонность"),
                    item(PurposeDto.EQUIPMENT, "Оборудование и развитие", "Разовая крупная покупка или проект"),
                    item(PurposeDto.CASH_GAP, "Кассовый разрыв", "Короткие деньги на срок до 60–90 дней"),
                    item(PurposeDto.REFINANCING, "Рефинансирование", "Погашение текущих кредитов в другом банке"),
                    item(PurposeDto.STARTUP, "Старт бизнеса", "Деньги для только что открывшегося бизнеса")))
            .fundingModes(List.of(
                    item(FundingModeDto.LUMP_SUM, "Вся сумма сразу"),
                    item(FundingModeDto.TRANCHES, "Частями"),
                    item(FundingModeDto.ON_DEMAND, "По мере необходимости")))
            .repaymentTypes(List.of(
                    item(RepaymentTypeDto.ANNUITY, "Равными платежами"),
                    item(RepaymentTypeDto.INTEREST_ONLY, "Только проценты, долг в конце срока")))
            .applicationStatuses(List.of(
                    item(ApplicationStatusDto.DRAFT, "Черновик"),
                    item(ApplicationStatusDto.SUBMITTED, "Отправлена"),
                    item(ApplicationStatusDto.ENRICHMENT, "Проверяем данные"),
                    item(ApplicationStatusDto.SCORING, "Оцениваем заявку"),
                    item(ApplicationStatusDto.NEED_INFO, "Нужны дополнительные сведения"),
                    item(ApplicationStatusDto.MANUAL_REVIEW, "На рассмотрении у специалиста"),
                    item(ApplicationStatusDto.APPROVED, "Одобрено"),
                    item(ApplicationStatusDto.COUNTER_OFFER, "Предложены другие условия"),
                    item(ApplicationStatusDto.PENDING_CONFIRMATION, "Ожидает подтверждения банка"),
                    item(ApplicationStatusDto.OFFER_ACCEPTED, "Готово к подписанию"),
                    item(ApplicationStatusDto.SIGNED, "Договор подписан"),
                    item(ApplicationStatusDto.DISBURSED, "Деньги выданы"),
                    item(ApplicationStatusDto.REJECTED, "Отказ"),
                    item(ApplicationStatusDto.OFFER_DECLINED, "Вы отказались от предложения"),
                    item(ApplicationStatusDto.OFFER_EXPIRED, "Срок предложения истёк"),
                    item(ApplicationStatusDto.CANCELLED, "Заявка отменена")))
            // Все коды причин (docs/scoring.md): политика, сбой обработки, изменение условий продукта, решения сотрудника
            .reasonCodes(List.of(
                    new DictionaryItemDto("COMPANY_NOT_FOUND", "Компания не найдена в ЕГРЮЛ/ЕГРИП"),
                    new DictionaryItemDto("SOURCE_UNAVAILABLE", "Внешний источник данных временно недоступен"),
                    new DictionaryItemDto("COMPANY_LIQUIDATING", "Компания в процессе ликвидации"),
                    new DictionaryItemDto("COMPANY_LIQUIDATED", "Компания ликвидирована"),
                    new DictionaryItemDto("OVERDUE_GT_60", "Есть просрочка по кредиту больше 60 дней"),
                    new DictionaryItemDto("TAX_DEBT_HIGH", "Налоговая задолженность выше допустимой"),
                    new DictionaryItemDto("PRODUCT_NOT_ELIGIBLE", "Продукт недоступен по возрасту бизнеса"),
                    new DictionaryItemDto("MISSING_FIELDS", "Не хватает сведений в анкете"),
                    new DictionaryItemDto("SCORE_LOW", "Оценка платёжеспособности ниже требуемой"),
                    new DictionaryItemDto("LOW_PROFITABILITY", "Низкая рентабельность бизнеса"),
                    new DictionaryItemDto("DEBT_LOAD_HIGH", "Высокая долговая нагрузка"),
                    new DictionaryItemDto("OVERDUE_HISTORY", "Были просрочки по кредитам"),
                    new DictionaryItemDto("TAX_DEBT", "Есть налоговая задолженность"),
                    new DictionaryItemDto("YOUNG_BUSINESS", "Небольшой срок работы бизнеса"),
                    new DictionaryItemDto("MANY_ACTIVE_CREDITS", "Много действующих кредитов"),
                    new DictionaryItemDto("SCORE_GREY_ZONE", "Требуется дополнительная проверка специалистом"),
                    new DictionaryItemDto("OVERDUE_31_60", "Была просрочка по кредиту от 31 до 60 дней"),
                    new DictionaryItemDto("LOSS_MAKING", "Расходы за год не меньше выручки"),
                    new DictionaryItemDto("INSUFFICIENT_FINANCES", "Финансовых показателей недостаточно для минимальной суммы продукта"),
                    new DictionaryItemDto("AMOUNT_ABOVE_LIMIT", "Запрошенная сумма больше доступного лимита"),
                    new DictionaryItemDto("PROCESSING_ERROR", "Автоматическая обработка не завершилась"),
                    new DictionaryItemDto("PRODUCT_CONDITIONS_CHANGED", "Условия продукта изменились после отправки заявки"),
                    new DictionaryItemDto("MANUAL_REJECT", "Отказ по итогам проверки специалистом"),
                    new DictionaryItemDto("MANUAL_COUNTER_OFFER", "Специалист банка предложил другие условия")));

    @Override
    public ResponseEntity<DictionariesDto> getDictionaries() {
        return ResponseEntity.ok(DICTIONARIES);
    }

    private static DictionaryItemDto item(Enum<?> code, String label) {
        return new DictionaryItemDto(code.name(), label);
    }

    private static DictionaryItemDto item(Enum<?> code, String label, String description) {
        return item(code, label).description(description);
    }
}
