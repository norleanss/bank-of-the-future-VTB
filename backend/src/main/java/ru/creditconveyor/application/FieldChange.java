package ru.creditconveyor.application;

/**
 * Поле в JSON Merge Patch: не передано — не меняется, {@code null} — очищается, значение — устанавливается.
 */
record FieldChange<T>(boolean present, T value) {

    private static final FieldChange<?> ABSENT = new FieldChange<>(false, null);

    @SuppressWarnings("unchecked")
    static <T> FieldChange<T> absent() {
        return (FieldChange<T>) ABSENT;
    }

    static <T> FieldChange<T> clear() {
        return new FieldChange<>(true, null);
    }

    static <T> FieldChange<T> set(T value) {
        return new FieldChange<>(true, value);
    }

    static <T> FieldChange<T> ofNullable(T value) {
        return value != null ? set(value) : absent();
    }

    boolean clears() {
        return present && value == null;
    }

    T applyTo(T current) {
        return present ? value : current;
    }
}
