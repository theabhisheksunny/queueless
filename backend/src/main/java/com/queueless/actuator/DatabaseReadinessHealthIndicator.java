package com.queueless.actuator;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.*;

/**
 * Dedicated Database Readiness Health Indicator
 * =====================================================================
 * Verifies active PostgreSQL database connectivity with strict execution bounds:
 * - Total health-check duration bounded to 3 seconds via asynchronous task timeout.
 * - Connection acquisition cannot hang indefinitely (bounded by future.get timeout and Hikari timeout).
 * - Query execution bounded by statement.setQueryTimeout(3) and "SELECT 1".
 * - Actively cancels running statement and closes connection on timeout to prevent leaking
 *   background database queries or accumulating stranded tasks.
 *
 * Security invariant: Never exposes database connection URLs, usernames,
 * credentials, or internal exception stack traces in health reports.
 * =====================================================================
 */
@Component("dbReadiness")
public class DatabaseReadinessHealthIndicator implements HealthIndicator, AutoCloseable {

    public static final int TIMEOUT_SECONDS = 3;
    private final DataSource dataSource;
    private final ExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired
    public DatabaseReadinessHealthIndicator(DataSource dataSource) {
        this(dataSource, Executors.newVirtualThreadPerTaskExecutor());
    }

    public DatabaseReadinessHealthIndicator(DataSource dataSource, ExecutorService executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public Health health() {
        if (dataSource == null) {
            return Health.down()
                    .withDetail("status", "UNCONFIGURED")
                    .build();
        }

        ConnectivityCheckTask task = new ConnectivityCheckTask(dataSource);
        Future<Health> future = executor.submit(task);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            task.abort();
            return Health.down()
                    .withDetail("status", "TIMEOUT")
                    .build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            task.abort();
            return Health.down()
                    .withDetail("status", "INTERRUPTED")
                    .build();
        } catch (ExecutionException ex) {
            return Health.down()
                    .withDetail("status", "UNAVAILABLE")
                    .build();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private static class ConnectivityCheckTask implements Callable<Health> {
        private final DataSource dataSource;
        private volatile Connection activeConnection;
        private volatile Statement activeStatement;

        ConnectivityCheckTask(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void abort() {
            Statement stmt = activeStatement;
            if (stmt != null) {
                try {
                    stmt.cancel();
                } catch (Throwable ignored) {
                }
            }
            Connection conn = activeConnection;
            if (conn != null) {
                try {
                    conn.close();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public Health call() {
            if (Thread.currentThread().isInterrupted()) {
                return Health.down().withDetail("status", "INTERRUPTED").build();
            }

            try (Connection connection = dataSource.getConnection()) {
                this.activeConnection = connection;
                if (Thread.currentThread().isInterrupted()) {
                    return Health.down().withDetail("status", "INTERRUPTED").build();
                }
                if (connection == null) {
                    return Health.down()
                            .withDetail("status", "DISCONNECTED")
                            .build();
                }

                try (Statement statement = connection.createStatement()) {
                    this.activeStatement = statement;
                    statement.setQueryTimeout(TIMEOUT_SECONDS);
                    if (Thread.currentThread().isInterrupted()) {
                        return Health.down().withDetail("status", "INTERRUPTED").build();
                    }
                    statement.execute("SELECT 1");
                    return Health.up()
                            .withDetail("status", "CONNECTED")
                            .build();
                }
            } catch (SQLException ex) {
                // Sanitize: Never include ex.getMessage() as it may contain host, port, or user info
                return Health.down()
                        .withDetail("status", "UNAVAILABLE")
                        .build();
            } catch (Exception ex) {
                return Health.down()
                        .withDetail("status", "UNAVAILABLE")
                        .build();
            } finally {
                this.activeStatement = null;
                this.activeConnection = null;
            }
        }
    }
}
