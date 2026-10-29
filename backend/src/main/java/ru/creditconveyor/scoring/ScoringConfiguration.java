package ru.creditconveyor.scoring;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ScoringProperties.class)
class ScoringConfiguration {

    /** Ошибка в настройках политики (неизвестное поле, нет метода лимита) останавливает старт. */
    @Bean
    CreditPolicy creditPolicy(ScoringProperties properties) {
        return new CreditPolicy(properties);
    }
}
