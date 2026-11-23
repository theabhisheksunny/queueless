package com.queueless.service;

import com.queueless.repository.CustomerPushSubscriptionRepository;
import com.queueless.repository.DeviceAuthChallengeRepository;
import com.queueless.repository.IdempotencyKeyRepository;
import com.queueless.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DataLifecycleMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DataLifecycleMaintenanceService.class);

    // Advisory lock key (64-bit integer unique to QueueLess maintenance)
    public static final long MAINTENANCE_LOCK_KEY = 882371946251L;
    public static final int BATCH_SIZE = 500;
    public static final int MAX_BATCHES = 20;

    private final DeviceAuthChallengeRepository challengeRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserSessionRepository userSessionRepository;
    private final CustomerPushSubscriptionRepository pushSubscriptionRepository;
    private final DataSource dataSource;

    @Value("${queueless.cleanup.on-startup:false}")
    private boolean cleanupOnStartup;

    @Autowired
    public DataLifecycleMaintenanceService(DeviceAuthChallengeRepository challengeRepository,
                                           IdempotencyKeyRepository idempotencyKeyRepository,
                                           UserSessionRepository userSessionRepository,
                                           CustomerPushSubscriptionRepository pushSubscriptionRepository,
                                           @Autowired(required = false) JdbcTemplate jdbcTemplate,
                                           @Autowired(required = false) DataSource dataSource) {
        this.challengeRepository = challengeRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.userSessionRepository = userSessionRepository;
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.dataSource = dataSource != null ? dataSource : (jdbcTemplate != null ? jdbcTemplate.getDataSource() : null);
    }

    /**
     * Daily maintenance task: purges expired ephemeral nonces, stale push subscriptions,
     * and expired idempotency tokens in bounded batches. Uses a dedicated PostgreSQL physical connection
     * with pg_try_advisory_lock to coordinate execution safely across multiple application instances.
     * Guarantees that:
     * 1. Lock acquisition and unlock execute on the exact same physical JDBC connection.
     * 2. The lock is always released in finally on that connection.
     * 3. Connection pooling cannot return a connection with an active advisory lock.
     * 4. Exceptions and interrupted cleanup do not leave a lock held.
     * 5. Another independent application instance can acquire the lock after completion or connection loss.
     * Never touches financial, payment, or audit logs.
     */
    @Scheduled(cron = "${queueless.cleanup.cron:0 0 3 * * *}")
    public CleanupStats performMaintenanceCleanup() {
        if (dataSource == null) {
            log.debug("DataSource unavailable. Executing lifecycle maintenance locally.");
            return executePurgeBatches();
        }

        Connection lockConn = null;
        boolean isPostgres = false;
        boolean lockAcquired = false;

        try {
            lockConn = dataSource.getConnection();
            lockConn.setAutoCommit(true);

            try {
                String productName = lockConn.getMetaData().getDatabaseProductName();
                isPostgres = productName != null && productName.toLowerCase().contains("postgres");
            } catch (Exception ignored) {}

            if (isPostgres) {
                try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                    ps.setLong(1, MAINTENANCE_LOCK_KEY);
                    try (ResultSet rs = ps.executeQuery()) {
                        lockAcquired = rs.next() && rs.getBoolean(1);
                    }
                }

                if (!lockAcquired) {
                    log.info("LIFECYCLE CLEANUP SKIPPED: Another application instance holds the PostgreSQL maintenance advisory lock.");
                    return new CleanupStats(0, 0, 0, 0, false);
                }
            } else {
                lockAcquired = true;
            }

            return executePurgeBatches();
        } catch (SQLException ex) {
            log.error("Database error during lifecycle maintenance: {}", ex.getMessage(), ex);
            throw new RuntimeException("Database error during lifecycle maintenance", ex);
        } finally {
            if (lockConn != null) {
                if (isPostgres && lockAcquired) {
                    try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, MAINTENANCE_LOCK_KEY);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && !rs.getBoolean(1)) {
                                log.warn("pg_advisory_unlock returned false for lock key {}", MAINTENANCE_LOCK_KEY);
                            }
                        }
                    } catch (Exception ex) {
                        log.error("CRITICAL: Failed to release PostgreSQL advisory lock in finally: {}. Aborting connection to prevent pool contamination.", ex.getMessage());
                        try {
                            lockConn.abort(Runnable::run);
                        } catch (Exception ignored) {}
                    }
                }
                try {
                    if (!lockConn.isClosed()) {
                        lockConn.close();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @Transactional
    public CleanupStats executePurgeBatches() {
        LocalDateTime now = LocalDateTime.now();

        // 1. Purge device auth challenges older than 24 hours in bounded batches
        int purgedChallenges = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Long> ids = challengeRepository.findExpiredIds(now.minusHours(24), PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) break;
            challengeRepository.deleteAllByIdInBatch(ids);
            purgedChallenges += ids.size();
            if (ids.size() < BATCH_SIZE) break;
        }

        // 2. Purge expired idempotency keys (TTL reached) in bounded batches
        int purgedIdempotency = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Long> ids = idempotencyKeyRepository.findExpiredIds(now, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) break;
            idempotencyKeyRepository.deleteAllByIdInBatch(ids);
            purgedIdempotency += ids.size();
            if (ids.size() < BATCH_SIZE) break;
        }

        // 3. Purge revoked user sessions older than 30 days in bounded batches
        int purgedSessions = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Long> ids = userSessionRepository.findStaleRevokedSessionIds(now.minusDays(30), PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) break;
            userSessionRepository.deleteAllByIdInBatch(ids);
            purgedSessions += ids.size();
            if (ids.size() < BATCH_SIZE) break;
        }

        // 4. Purge inactive push subscriptions older than 30 days in bounded batches
        int purgedPushSubs = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Long> ids = pushSubscriptionRepository.findStaleSubscriptionIds(now.minusDays(30), PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) break;
            pushSubscriptionRepository.deleteAllByIdInBatch(ids);
            purgedPushSubs += ids.size();
            if (ids.size() < BATCH_SIZE) break;
        }

        log.info("LIFECYCLE CLEANUP COMPLETE: purged {} expired challenges, {} idempotency keys, {} revoked sessions, {} stale push subscriptions",
                purgedChallenges, purgedIdempotency, purgedSessions, purgedPushSubs);

        return new CleanupStats(purgedChallenges, purgedIdempotency, purgedSessions, purgedPushSubs, true);
    }

    /**
     * Optional startup catch-up trigger for ephemeral or scale-to-zero environments (such as Render Free tier).
     * When instances spin down during scheduled maintenance hours (e.g. 03:00 AM UTC/IST),
     * enabling this flag guarantees stale tokens and nonces are cleaned up asynchronously upon container wake-up
     * without blocking application readiness or HTTP health probes.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (cleanupOnStartup) {
            log.info("QueueLess startup cleanup enabled: triggering asynchronous maintenance catch-up...");
            CompletableFuture.runAsync(() -> {
                try {
                    performMaintenanceCleanup();
                } catch (Exception ex) {
                    log.warn("Asynchronous startup catch-up maintenance encountered an error: {}", ex.getMessage());
                }
            });
        }
    }

    public boolean isCleanupOnStartup() {
        return cleanupOnStartup;
    }

    public void setCleanupOnStartup(boolean cleanupOnStartup) {
        this.cleanupOnStartup = cleanupOnStartup;
    }

    public record CleanupStats(int purgedChallenges, int purgedIdempotencyKeys, int purgedSessions, int purgedPushSubscriptions, boolean executed) {
        public CleanupStats(int purgedChallenges, int purgedIdempotencyKeys, int purgedSessions, int purgedPushSubscriptions) {
            this(purgedChallenges, purgedIdempotencyKeys, purgedSessions, purgedPushSubscriptions, true);
        }
    }
}
