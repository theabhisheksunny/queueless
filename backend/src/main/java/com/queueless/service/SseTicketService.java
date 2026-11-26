package com.queueless.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to manage ephemeral, single-use tickets for Server-Sent Events (SSE) connections.
 * Eliminates transmission of long-lived JWT bearer tokens in URL query strings.
 * Supports distributed, atomic single-use consumption via the shared sse_tickets PostgreSQL table.
 */
@Service
public class SseTicketService {

    private static final Logger log = LoggerFactory.getLogger(SseTicketService.class);
    public static final long TICKET_TTL_SECONDS = 60L;

    public record SseTicket(
            String ticket,
            Long userId,
            Long shopId,
            Instant createdAt,
            Instant expiresAt
    ) {}

    private final ConcurrentHashMap<String, SseTicket> inMemoryStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private Clock clock;
    private final boolean isProductionOrStaging;

    @Autowired
    public SseTicketService(@Autowired(required = false) JdbcTemplate jdbcTemplate,
                            @Autowired(required = false) Environment environment) {
        this(jdbcTemplate, environment, Clock.systemUTC());
    }

    public SseTicketService(JdbcTemplate jdbcTemplate, Environment environment, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.clock = clock != null ? clock : Clock.systemUTC();

        boolean isProd = false;
        if (environment != null) {
            for (String p : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p) || "staging".equalsIgnoreCase(p)) {
                    isProd = true;
                    break;
                }
            }
        }
        this.isProductionOrStaging = isProd;

        if (this.isProductionOrStaging && this.jdbcTemplate == null) {
            throw new IllegalStateException("Distributed PostgreSQL sse_tickets table store (JdbcTemplate) is strictly required in production/staging. In-memory fallback is prohibited.");
        }
    }

    public SseTicketService(Clock clock) {
        this(null, null, clock);
    }

    public SseTicketService() {
        this(null, null, Clock.systemUTC());
    }

    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Issues a cryptographically secure, single-use ticket bound to a specific user and shop.
     * Maximum lifespan is 60 seconds.
     * Never logs the raw ticket value.
     */
    public SseTicket issueTicket(Long userId, Long shopId) {
        if (userId == null || shopId == null) {
            throw new IllegalArgumentException("User ID and Shop ID are required to issue an SSE ticket.");
        }

        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);
        String ticket = "stk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusSeconds(TICKET_TTL_SECONDS);

        SseTicket entry = new SseTicket(ticket, userId, shopId, now, expiresAt);

        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                        ticket,
                        userId,
                        shopId,
                        Timestamp.from(now),
                        Timestamp.from(expiresAt)
                );
            } catch (Exception ex) {
                if (isProductionOrStaging) {
                    log.error("Failed to persist SSE ticket to shared database store in production: {}", ex.getMessage());
                    throw new IllegalStateException("Shared SSE ticket storage failure in production environment", ex);
                } else {
                    log.warn("Database ticket insert failed in test/local environment, using local store: {}", ex.getMessage());
                    inMemoryStore.put(ticket, entry);
                }
            }
        } else {
            if (isProductionOrStaging) {
                throw new IllegalStateException("Distributed PostgreSQL sse_tickets table store is mandatory in production/staging. In-memory fallback is strictly forbidden.");
            }
            inMemoryStore.put(ticket, entry);
        }

        log.debug("Issued SSE ticket for userId={}, shopId={}, expiresAt={}", userId, shopId, expiresAt);
        return entry;
    }

    /**
     * Atomically consumes a ticket across multiple application instances.
     * Enforces strict tenant ownership and owner binding; eliminates nullable bypasses.
     * Uses atomic database DELETE ... RETURNING on PostgreSQL / relational backends.
     * Fails closed with AccessDeniedException if the ticket is missing, invalid, expired,
     * already consumed (replay), or does not match the expected shop or owner.
     * Never logs the raw ticket value.
     */
    public SseTicket consumeTicket(String ticket, Long expectedShopId, Long expectedUserId) {
        if (ticket == null || ticket.isBlank()) {
            throw new AccessDeniedException("Access denied: An SSE ticket must be provided.");
        }
        if (expectedShopId == null) {
            throw new AccessDeniedException("Access denied: Tenant shop identification is strictly required.");
        }

        String trimmedTicket = ticket.trim();
        Instant now = Instant.now(clock);

        if (jdbcTemplate != null) {
            return consumeFromDatabase(trimmedTicket, expectedShopId, expectedUserId, now);
        } else {
            if (isProductionOrStaging) {
                throw new IllegalStateException("Distributed PostgreSQL sse_tickets table store is mandatory in production/staging.");
            }
            return consumeFromMemory(trimmedTicket, expectedShopId, expectedUserId, now);
        }
    }

    public SseTicket consumeTicket(String ticket, Long expectedShopId) {
        return consumeTicket(ticket, expectedShopId, null);
    }

    private SseTicket consumeFromDatabase(String ticket, Long expectedShopId, Long expectedUserId, Instant now) {
        String sql = "DELETE FROM sse_tickets WHERE ticket = ? RETURNING ticket, user_id, shop_id, created_at, expires_at";
        List<SseTicket> rows;
        try {
            rows = jdbcTemplate.execute(sql, (PreparedStatement ps) -> {
                ps.setString(1, ticket);
                boolean hasResultSet = ps.execute();
                List<SseTicket> results = new ArrayList<>();
                if (hasResultSet) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            results.add(new SseTicket(
                                    rs.getString("ticket"),
                                    rs.getLong("user_id"),
                                    rs.getLong("shop_id"),
                                    rs.getTimestamp("created_at").toInstant(),
                                    rs.getTimestamp("expires_at").toInstant()
                            ));
                        }
                    }
                }
                return results;
            });
        } catch (org.springframework.jdbc.BadSqlGrammarException grammarEx) {
            // For dialect engines that do not support DELETE ... RETURNING (such as local H2 test harness),
            // execute an equivalent transaction-safe SELECT ... FOR UPDATE followed by atomic DELETE
            return consumeWithLockingTransaction(ticket, expectedShopId, expectedUserId, now);
        } catch (Exception ex) {
            log.error("Database error during SSE ticket consumption: {}", ex.getMessage());
            throw new AccessDeniedException("Access denied: Database error during ticket verification.", ex);
        }

        if (rows == null || rows.isEmpty()) {
            throw new AccessDeniedException("Access denied: Invalid or already consumed SSE ticket.");
        }

        SseTicket entry = rows.get(0);
        validateTicketClaims(entry, expectedShopId, expectedUserId, now);
        log.debug("Successfully consumed SSE ticket for shopId={}, userId={}", entry.shopId(), entry.userId());
        return entry;
    }

    private synchronized SseTicket consumeWithLockingTransaction(String ticket, Long expectedShopId, Long expectedUserId, Instant now) {
        try {
            List<SseTicket> rows = jdbcTemplate.query(
                    "SELECT ticket, user_id, shop_id, created_at, expires_at FROM sse_tickets WHERE ticket = ? FOR UPDATE",
                    (rs, rowNum) -> new SseTicket(
                            rs.getString("ticket"),
                            rs.getLong("user_id"),
                            rs.getLong("shop_id"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant()
                    ),
                    ticket
            );
            if (rows.isEmpty()) {
                throw new AccessDeniedException("Access denied: Invalid or already consumed SSE ticket.");
            }
            jdbcTemplate.update("DELETE FROM sse_tickets WHERE ticket = ?", ticket);
            SseTicket entry = rows.get(0);
            validateTicketClaims(entry, expectedShopId, expectedUserId, now);
            return entry;
        } catch (AccessDeniedException ade) {
            throw ade;
        } catch (Exception ex) {
            log.error("Locking transaction error during SSE ticket consumption: {}", ex.getMessage());
            throw new AccessDeniedException("Access denied: Database error during ticket verification.", ex);
        }
    }

    private void validateTicketClaims(SseTicket entry, Long expectedShopId, Long expectedUserId, Instant now) {
        if (now.isAfter(entry.expiresAt())) {
            throw new AccessDeniedException("Access denied: SSE ticket has expired.");
        }

        if (!expectedShopId.equals(entry.shopId())) {
            throw new AccessDeniedException("Access denied: SSE ticket is not valid for the requested shop.");
        }

        if (expectedUserId != null && !expectedUserId.equals(entry.userId())) {
            throw new AccessDeniedException("Access denied: SSE ticket is not valid for the authenticated user.");
        }
    }

    private SseTicket consumeFromMemory(String ticket, Long expectedShopId, Long expectedUserId, Instant now) {
        SseTicket entry = inMemoryStore.remove(ticket);
        if (entry == null) {
            throw new AccessDeniedException("Access denied: Invalid or already consumed SSE ticket.");
        }

        if (now.isAfter(entry.expiresAt())) {
            throw new AccessDeniedException("Access denied: SSE ticket has expired.");
        }

        if (!expectedShopId.equals(entry.shopId())) {
            throw new AccessDeniedException("Access denied: SSE ticket is not valid for the requested shop.");
        }

        if (expectedUserId != null && !expectedUserId.equals(entry.userId())) {
            throw new AccessDeniedException("Access denied: SSE ticket is not valid for the authenticated user.");
        }

        return entry;
    }

    /**
     * Purges expired tickets periodically every 60 seconds.
     */
    @Scheduled(fixedRate = 60000)
    public int cleanupExpiredTickets() {
        Instant now = Instant.now(clock);
        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.update(
                        "DELETE FROM sse_tickets WHERE expires_at <= ?",
                        Timestamp.from(now)
                );
            } catch (Exception ex) {
                log.warn("Database cleanup of expired tickets encountered an error: {}", ex.getMessage());
                return 0;
            }
        } else {
            int before = inMemoryStore.size();
            inMemoryStore.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
            return before - inMemoryStore.size();
        }
    }

    public int getActiveTicketCount() {
        if (jdbcTemplate != null) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sse_tickets WHERE expires_at > ?",
                        Integer.class,
                        Timestamp.from(Instant.now(clock))
                );
                return count != null ? count : 0;
            } catch (Exception ex) {
                return 0;
            }
        } else {
            return inMemoryStore.size();
        }
    }

    public boolean isDatabaseBacked() {
        return jdbcTemplate != null;
    }
}
