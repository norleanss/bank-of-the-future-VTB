package ru.creditconveyor.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Код подписания договора (моделирование без SMS). В базе только хеш; после исчерпания попыток код блокируется.
 * {@code @Version}: из одновременных вводов второй получает 409 — попытки не теряются.
 */
@Entity
@Table(schema = "application", name = "signing_codes")
class SigningCode {

    static final String ACTIVE = "ACTIVE";
    static final String BLOCKED = "BLOCKED";

    enum Check {
        VALID,
        WRONG,
        BLOCKED,
        EXPIRED
    }

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Version
    private long version;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SigningCode() {
    }

    SigningCode(UUID applicationId, String code, Instant now, Duration ttl) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.codeHash = hash(applicationId, code);
        this.status = ACTIVE;
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    /** Неверный ввод считается попыткой; последняя неудачная блокирует код. */
    Check check(String code, Instant now, int maxAttempts) {
        if (!now.isBefore(expiresAt)) {
            return Check.EXPIRED;
        }
        if (MessageDigest.isEqual(codeHash.getBytes(StandardCharsets.UTF_8),
                hash(applicationId, code).getBytes(StandardCharsets.UTF_8))) {
            status = "USED";
            return Check.VALID;
        }
        attempts++;
        if (attempts >= maxAttempts) {
            status = BLOCKED;
            return Check.BLOCKED;
        }
        return Check.WRONG;
    }

    void supersede() {
        status = "SUPERSEDED";
    }

    int getAttempts() {
        return attempts;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    private static String hash(UUID applicationId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((applicationId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
