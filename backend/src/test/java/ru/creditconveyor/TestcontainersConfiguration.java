package ru.creditconveyor;

import java.time.Instant;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL той же версии, что в docker-compose, и управляемые часы.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Та же опорная дата, что у DS: возраст бизнеса считается на 2026-11-01. */
    public static final Instant TODAY = Instant.parse("2026-11-01T09:00:00Z");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"));
    }

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock(TODAY, ZoneId.of("Europe/Moscow"));
    }
}
