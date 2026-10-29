package ru.creditconveyor.application;

import java.io.Serial;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400 с ошибками по полям в том же формате, что у Bean Validation.
 */
public class InvalidApplicationException extends ErrorResponseException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidApplicationException(String detail, Map<String, String> errors) {
        super(HttpStatus.BAD_REQUEST, problem(detail, errors), null);
    }

    private static ProblemDetail problem(String detail, Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("https://credit.example/problems/validation"));
        List<Map<String, String>> fieldErrors = errors.entrySet().stream()
                .map(error -> Map.of("field", error.getKey(), "message", error.getValue()))
                .toList();
        problem.setProperty("errors", fieldErrors);
        return problem;
    }
}
