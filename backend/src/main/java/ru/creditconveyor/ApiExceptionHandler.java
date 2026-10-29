package ru.creditconveyor;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.ValueInstantiationException;

/**
 * Ошибки API в формате Problem Details: русские заголовки, для 400 — {@code errors} с путём к полю через точку.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<Integer, String> TITLES = Map.of(
            400, "Ошибка валидации",
            401, "Требуется вход",
            403, "Нет доступа",
            404, "Не найдено",
            405, "Метод не поддерживается",
            409, "Действие недоступно",
            413, "Файл слишком большой",
            415, "Формат не поддерживается",
            500, "Внутренняя ошибка");

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), String.valueOf(error.getDefaultMessage())));
        return validationError(ex, "Некоторые поля заполнены неверно", errors, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        JacksonException jackson = findCause(ex, JacksonException.class);
        if (jackson != null && !jackson.getPath().isEmpty()) {
            Class<?> targetType = switch (jackson) {
                case MismatchedInputException mismatch -> mismatch.getTargetType();
                // Сгенерированные перечисления отвергают неизвестный код в fromValue
                case ValueInstantiationException instantiation -> instantiation.getType().getRawClass();
                default -> null;
            };
            errors.put(path(jackson.getPath()), invalidValue(targetType));
        }
        return validationError(ex, "Тело запроса не удалось прочитать: проверьте JSON и значения полей",
                errors, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : ex.getPropertyName();
        return validationError(ex, "Некоторые параметры запроса заданы неверно",
                Map.of(String.valueOf(field), invalidValue(ex.getRequiredType())), headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors && parameterErrors.hasFieldErrors()) {
                for (FieldError error : parameterErrors.getFieldErrors()) {
                    errors.putIfAbsent(error.getField(), String.valueOf(error.getDefaultMessage()));
                }
            } else {
                String name = String.valueOf(result.getMethodParameter().getParameterName());
                result.getResolvableErrors()
                        .forEach(error -> errors.putIfAbsent(name, String.valueOf(error.getDefaultMessage())));
            }
        });
        return validationError(ex, "Некоторые параметры запроса заданы неверно", errors, headers, request);
    }

    /** Сгенерированные интерфейсы помечены {@code @Validated}: параметры проверяет AOP-перехватчик, а не MVC. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String field = null;
            for (Path.Node node : violation.getPropertyPath()) {
                field = node.getName();
            }
            errors.putIfAbsent(String.valueOf(field), violation.getMessage());
        });
        return validationError(ex, "Некоторые параметры запроса заданы неверно", errors, new HttpHeaders(), request);
    }

    /** Сюда же приходят ошибки из фильтров Spring Security (см. {@code SecurityConfig}). */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Нужен действующий токен: войдите через POST /auth/demo-login");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return handleExceptionInternal(ex, body, headers, HttpStatus.UNAUTHORIZED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        String detail = ex instanceof AuthorizationDeniedException || ex.getMessage() == null
                ? "Действие недоступно для вашей роли"
                : ex.getMessage();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    /** {@code @Version}: из двух одновременных изменений заявки второе получает 409. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleConcurrentModification(OptimisticLockingFailureException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Заявка только что изменилась — обновите данные и повторите действие");
        body.setType(URI.create("https://credit.example/problems/concurrent-modification"));
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Файл больше допустимого размера");
        return handleExceptionInternal(ex, body, headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationError(ex, "Некоторые поля заполнены неверно",
                Map.of(ex.getRequestPartName(), "Обязательное поле"), headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationError(ex, "Некоторые поля заполнены неверно",
                Map.of(ex.getParameterName(), "Обязательное поле"), headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return notFound(ex, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return notFound(ex, headers, request);
    }

    /** {@code headers} уже содержат {@code Allow} с допустимыми методами. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String supported = ex.getSupportedHttpMethods() == null ? "" : ex.getSupportedHttpMethods().stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ", ". Допустимые: ", ""));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "Метод " + ex.getMethod() + " для этого адреса не поддерживается" + supported);
        return handleExceptionInternal(ex, body, headers, HttpStatus.METHOD_NOT_ALLOWED, request);
    }

    /** {@code headers} уже содержат {@code Accept} с допустимыми форматами. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String supported = ex.getSupportedMediaTypes().isEmpty() ? "" : ex.getSupportedMediaTypes().stream()
                .map(MediaType::toString)
                .collect(Collectors.joining(", ", ". Допустимые: ", ""));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Формат тела запроса " + (ex.getContentType() == null ? "не указан" : ex.getContentType() + " не поддерживается")
                        + supported);
        return handleExceptionInternal(ex, body, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    /** Подробности — только в лог, наружу их не отдаём. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Необработанная ошибка", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Не удалось выполнить запрос, попробуйте позже");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<Object> validationError(Exception ex, String detail, Map<String, String> fieldErrors,
                                                   HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        body.setType(URI.create("https://credit.example/problems/validation"));
        if (!fieldErrors.isEmpty()) {
            body.setProperty("errors", fieldErrors.entrySet().stream()
                    .map(error -> Map.of("field", error.getKey(), "message", error.getValue()))
                    .toList());
        }
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> notFound(Exception ex, HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Такого адреса нет в API");
        return handleExceptionInternal(ex, body, headers, HttpStatus.NOT_FOUND, request);
    }

    private static String invalidValue(@Nullable Class<?> targetType) {
        if (targetType != null && targetType.isEnum()) {
            return "Недопустимое значение. Допустимые: " + Arrays.stream(targetType.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
        }
        return "Недопустимое значение";
    }

    private static String path(List<JacksonException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : references) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            HttpStatus status = HttpStatus.resolve(problem.getStatus());
            boolean defaultTitle = problem.getTitle() == null
                    || (status != null && status.getReasonPhrase().equals(problem.getTitle()));
            if (defaultTitle && TITLES.containsKey(problem.getStatus())) {
                problem.setTitle(TITLES.get(problem.getStatus()));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
