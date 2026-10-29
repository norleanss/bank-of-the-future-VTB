package ru.creditconveyor.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выпуск JWT для демо-пользователей. Пароля нет, но роли и доступ дальше проверяются по токену.
 */
@Service
@Transactional(readOnly = true)
public class AccessTokens {

    public record IssuedToken(String accessToken, long expiresInSeconds, CurrentUser user) {
    }

    private final UserAccountRepository users;
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    AccessTokens(UserAccountRepository users, JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issueFor(String login) {
        CurrentUser user = users.findByLogin(login)
                .map(UserAccount::toCurrentUser)
                .orElseThrow(() -> new IllegalStateException("Демо-пользователь '" + login + "' не найден"));
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(JwtClaims.NAME, user.fullName())
                .claim(JwtClaims.ROLES, List.of(user.role().getValue()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds(), user);
    }
}
