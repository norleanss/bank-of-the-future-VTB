package ru.creditconveyor.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ru.creditconveyor.api.ApplicationPatchRequestDto;

/**
 * Сохраняет тело {@code PATCH} заявки ещё и как дерево JSON: сгенерированный DTO не отличает
 * «поле не передано» от {@code null}, а для JSON Merge Patch это нужно.
 * Не {@code JsonNullable}: {@code openApiNullable} — глобальный переключатель генератора и ещё одна
 * библиотека, её совместимость с Jackson 3 не проверена.
 */
@ControllerAdvice
class MergePatchBodyAdvice extends RequestBodyAdviceAdapter {

    private static final String DOCUMENT = MergePatchBodyAdvice.class.getName() + ".document";

    private final JsonMapper jsonMapper;

    MergePatchBodyAdvice(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == ApplicationPatchRequestDto.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = message.getBody().readAllBytes();
        try {
            RequestContextHolder.currentRequestAttributes()
                    .setAttribute(DOCUMENT, jsonMapper.readTree(body), RequestAttributes.SCOPE_REQUEST);
        } catch (JacksonException e) {
            // Некорректный JSON — ответ 400 даст обычное чтение тела в DTO
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    static @Nullable JsonNode currentDocument() {
        return (JsonNode) RequestContextHolder.currentRequestAttributes().getAttribute(DOCUMENT, RequestAttributes.SCOPE_REQUEST);
    }
}
