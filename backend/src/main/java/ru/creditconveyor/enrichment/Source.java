package ru.creditconveyor.enrichment;

/**
 * Внешний источник и путь запроса по ИНН.
 */
enum Source {
    EGRUL("/companies/{inn}"),
    FNS("/companies/{inn}/tax-debts"),
    BKI("/companies/{inn}/credit-report");

    private final String path;

    Source(String path) {
        this.path = path;
    }

    String path() {
        return path;
    }
}
