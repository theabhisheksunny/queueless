package com.queueless.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long validityInMilliseconds;
    private Clock clock = Clock.systemUTC();

    public JwtTokenProvider(
            @Value("${app.jwt.secret:queueless-secret-token-key-must-be-at-least-32-bytes-long!}") String secret,
            @Value("${app.jwt.expiration-ms:2592000000}") long validityInMilliseconds) { // 30 days
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validityInMilliseconds = validityInMilliseconds;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        long nowMillis = clock.millis();
        Date now = new Date(nowMillis);
        Date validity = new Date(nowMillis + validityInMilliseconds);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(validity)
                .signWith(key)
                .compact();
    }

    public String generateTokenForUsername(String username) {
        return generateTokenForUsername(username, null);
    }

    public String generateTokenForUsername(String username, String sessionId) {
        long nowMillis = clock.millis();
        Date now = new Date(nowMillis);
        Date validity = new Date(nowMillis + validityInMilliseconds);

        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(validity);
        if (sessionId != null) {
            builder.claim("sid", sessionId);
        }

        return builder.signWith(key).compact();
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .clock(() -> Date.from(clock.instant()))
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public String getSessionIdFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.get("sid", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Date getIssuedAt(String token) {
        Claims claims = Jwts.parser()
                .clock(() -> Date.from(clock.instant()))
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getIssuedAt();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
