package ru.creditconveyor.application;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
@EnableConfigurationProperties({SigningProperties.class, DocumentProperties.class})
class ApplicationConfiguration {

    /** Запас на остальные поля формы загрузки документа. */
    private static final DataSize FORM_FIELDS = DataSize.ofMegabytes(1);

    /** Лимиты multipart — от {@code app.documents.max-size}: больше — 413, а не обрыв или поздний отказ. */
    @Bean
    MultipartConfigElement multipartConfigElement(DocumentProperties documents) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(documents.maxSize());
        factory.setMaxRequestSize(DataSize.ofBytes(documents.maxSize().toBytes() + FORM_FIELDS.toBytes()));
        return factory.createMultipartConfig();
    }
}
