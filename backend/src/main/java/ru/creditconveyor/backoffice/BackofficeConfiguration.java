package ru.creditconveyor.backoffice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BackofficeProperties.class)
class BackofficeConfiguration {
}
