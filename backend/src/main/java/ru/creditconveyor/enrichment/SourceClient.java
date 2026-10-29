package ru.creditconveyor.enrichment;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Запрос к источнику с таймаутами и повторами. Повторяем только то, что может пройти со второго раза:
 * сетевые ошибки, таймауты и ответы 5xx; 404 и другие 4xx — сразу результат.
 */
@Component
class SourceClient {

    private static final Logger log = LoggerFactory.getLogger(SourceClient.class);

    /**
     * @param body       тело ответа как есть (для карточки андеррайтера); нет, если источник не ответил
     * @param httpStatus нет, если ответа не было
     */
    record Response(Source source, SourceStatus status, int attempts, @Nullable Integer httpStatus,
                    @Nullable String body, @Nullable String error, Instant requestedAt, @Nullable Instant receivedAt) {
    }

    private final Map<Source, RestClient> clients = new EnumMap<>(Source.class);
    private final RetryTemplate retry;
    private final Clock clock;

    SourceClient(IntegrationProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        for (Source source : Source.values()) {
            clients.put(source, RestClient.builder().baseUrl(properties.baseUrl(source)).requestFactory(requestFactory).build());
        }
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.retries())
                .delay(properties.retryDelay())
                .predicate(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build());
        this.clock = clock;
    }

    Response fetch(Source source, String inn) {
        Instant requestedAt = clock.instant();
        AtomicInteger attempts = new AtomicInteger();
        try {
            String body = retry.execute(() -> {
                attempts.incrementAndGet();
                return clients.get(source).get().uri(source.path(), inn).retrieve().body(String.class);
            });
            return new Response(source, SourceStatus.OK, attempts.get(), 200, body, null, requestedAt, clock.instant());
        } catch (RetryException e) {
            Throwable last = e.getLastException() != null ? e.getLastException() : e.getCause();
            Response failure = failure(source, attempts.get(), last, requestedAt);
            if (failure.status() == SourceStatus.NOT_FOUND) {
                log.info("Источник {}: по ИНН {} данных нет", source, masked(inn));
            } else {
                log.warn("Источник {} не ответил по ИНН {} после {} попыток: {}", source, masked(inn), attempts.get(),
                        last != null ? last.getMessage() : e.getMessage());
            }
            return failure;
        }
    }

    /** ИНН ИП — персональные данные: в логе только последние 4 цифры. */
    static String masked(String inn) {
        return inn.length() <= 4 ? "****" : "*".repeat(inn.length() - 4) + inn.substring(inn.length() - 4);
    }

    private Response failure(Source source, int attempts, @Nullable Throwable error, Instant requestedAt) {
        if (error instanceof HttpClientErrorException.NotFound notFound) {
            return new Response(source, SourceStatus.NOT_FOUND, attempts, 404, notFound.getResponseBodyAsString(),
                    null, requestedAt, clock.instant());
        }
        if (error instanceof HttpStatusCodeException statusError) {
            return new Response(source, SourceStatus.ERROR, attempts, statusError.getStatusCode().value(),
                    statusError.getResponseBodyAsString(), "Ответ " + statusError.getStatusCode().value(),
                    requestedAt, clock.instant());
        }
        boolean timeout = error instanceof ResourceAccessException && error.getCause() instanceof SocketTimeoutException;
        return new Response(source, timeout ? SourceStatus.TIMEOUT : SourceStatus.ERROR, attempts, null, null,
                timeout ? "Нет ответа за отведённое время" : "Источник недоступен", requestedAt, null);
    }
}
