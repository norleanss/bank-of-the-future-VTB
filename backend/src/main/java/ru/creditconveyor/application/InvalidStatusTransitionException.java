package ru.creditconveyor.application;

import java.io.Serial;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidStatusTransitionException extends ErrorResponseException {

    @Serial
    private static final long serialVersionUID = 1L;

    private InvalidStatusTransitionException(String detail) {
        super(HttpStatus.CONFLICT, problem(detail), null);
    }

    static InvalidStatusTransitionException transition(ApplicationStatus from, ApplicationStatus to) {
        return new InvalidStatusTransitionException("Переход из статуса " + from + " в статус " + to + " недопустим");
    }

    public static InvalidStatusTransitionException action(String action, ApplicationStatus status) {
        return new InvalidStatusTransitionException("Нельзя " + action + " в статусе " + status);
    }

    private static ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setType(URI.create("https://credit.example/problems/invalid-transition"));
        problem.setTitle("Действие недоступно");
        return problem;
    }
}
