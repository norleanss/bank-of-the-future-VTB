package ru.creditconveyor;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Модульный монолит: каждый подпакет — модуль Spring Modulith, {@code api} — общий код, сгенерированный из контракта.
 */
@Modulithic(systemName = "Кредитный конвейер МСБ", sharedModules = "api")
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class CreditConveyorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditConveyorApplication.class, args);
    }

    /** Все бизнес-даты берутся отсюда, чтобы тесты могли переставлять часы. */
    @Bean
    Clock clock(@Value("${app.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
