package ru.creditconveyor.identity;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Откуда фронтенд может обращаться к API ({@code app.cors.allowed-origins}, переменная {@code CORS_ALLOWED_ORIGINS}).
 */
@ConfigurationProperties("app.cors")
record CorsProperties(List<String> allowedOrigins) {
}
