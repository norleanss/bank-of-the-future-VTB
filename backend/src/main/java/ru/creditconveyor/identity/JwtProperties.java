package ru.creditconveyor.identity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки JWT ({@code app.security.jwt}). Пустой {@code secret} — случайный ключ при каждом старте.
 */
@ConfigurationProperties("app.security.jwt")
record JwtProperties(String secret, Duration ttl, String issuer) {
}
