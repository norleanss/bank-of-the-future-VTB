package ru.creditconveyor.backoffice;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.creditconveyor.api.RateUnitDto;

/**
 * Решения сотрудника ({@code app.backoffice}): наибольшая ставка, которую он может назначить, — по единице ставки
 * продукта (в год или в день).
 */
@ConfigurationProperties("app.backoffice")
record BackofficeProperties(Map<RateUnitDto, BigDecimal> maxRate) {

    BackofficeProperties {
        if (maxRate == null || !maxRate.keySet().containsAll(EnumSet.allOf(RateUnitDto.class))) {
            throw new IllegalArgumentException("app.backoffice.max-rate: нужна наибольшая ставка для каждой единицы "
                    + EnumSet.allOf(RateUnitDto.class));
        }
    }
}
