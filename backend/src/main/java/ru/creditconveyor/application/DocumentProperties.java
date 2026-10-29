package ru.creditconveyor.application;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Документы заявки ({@code app.documents}): размер файла, допустимые форматы и сколько документов у заявки.
 */
@ConfigurationProperties("app.documents")
record DocumentProperties(DataSize maxSize, List<String> allowedTypes, int maxPerApplication) {
}
