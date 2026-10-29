package ru.creditconveyor.enrichment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Адреса моков ЕГРЮЛ, ФНС и БКИ ({@code integrations}), таймауты и повторы.
 *
 * @param retries    сколько раз повторить запрос после ошибки сети, таймаута или ответа 5xx
 * @param retryDelay пауза между попытками
 */
@ConfigurationProperties("integrations")
record IntegrationProperties(
        Endpoint egrul,
        Endpoint fns,
        Endpoint bki,
        Duration connectTimeout,
        Duration readTimeout,
        int retries,
        Duration retryDelay) {

    record Endpoint(String baseUrl) {
    }

    String baseUrl(Source source) {
        return switch (source) {
            case EGRUL -> egrul.baseUrl();
            case FNS -> fns.baseUrl();
            case BKI -> bki.baseUrl();
        };
    }
}
