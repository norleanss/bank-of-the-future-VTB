package ru.creditconveyor.application;

import java.io.Serial;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Дата истечения — в тексте и в {@code expiresAt}, в поясе приложения, как все даты API.
 */
public class OfferExpiredException extends ErrorResponseException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    OfferExpiredException(Instant expiresAt, ZoneId zone) {
        super(HttpStatus.CONFLICT, problem(OffsetDateTime.ofInstant(expiresAt, zone)), null);
    }

    private static ProblemDetail problem(OffsetDateTime expiresAt) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Предложение действовало до " + DATE.format(expiresAt) + ". Вы можете подать новую заявку");
        problem.setType(URI.create("https://credit.example/problems/offer-expired"));
        problem.setTitle("Срок предложения истёк");
        problem.setProperty("expiresAt", expiresAt.toString());
        return problem;
    }
}
