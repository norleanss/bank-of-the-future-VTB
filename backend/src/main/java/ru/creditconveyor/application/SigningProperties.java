package ru.creditconveyor.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Код подписания договора ({@code app.signing}): срок жизни и число попыток ввода.
 */
@ConfigurationProperties("app.signing")
record SigningProperties(Duration codeTtl, int maxAttempts) {
}
