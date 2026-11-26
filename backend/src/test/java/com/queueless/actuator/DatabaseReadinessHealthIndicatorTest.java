package com.queueless.actuator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseReadinessHealthIndicatorTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    static class StubDataSource implements DataSource {
        private final ConnectionSupplier supplier;

        StubDataSource(ConnectionSupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return supplier.get();
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private Connection createMockConnection(boolean querySuccess, boolean timeout) {
        Statement stmtProxy = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("execute".equals(name)) {
                        if (timeout) {
                            throw new SQLTimeoutException("Connection timed out after 3000ms");
                        }
                        if (!querySuccess) {
                            throw new SQLException("Query failed");
                        }
                        return true;
                    }
                    if ("setQueryTimeout".equals(name)) {
                        return null;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("createStatement".equals(name)) {
                        return stmtProxy;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );
    }

    @Test
    @DisplayName("Database UP: Returns Status.UP and sanitized CONNECTED status")
    void testDatabaseUp() {
        Connection conn = createMockConnection(true, false);
        DataSource ds = new StubDataSource(() -> conn);
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(ds, executor);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("CONNECTED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Database DOWN: Returns Status.DOWN and never leaks credentials or internal errors")
    void testDatabaseDown_SanitizedException() {
        String sensitiveError = "FATAL: password authentication failed for user 'supabase_admin' on host 'db.ref.supabase.co:5432'";
        DataSource ds = new StubDataSource(() -> {
            throw new SQLException(sensitiveError);
        });
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(ds, executor);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("UNAVAILABLE", health.getDetails().get("status"));

        // Strict security assertion: Sensitive connection details must never leak
        String detailsString = health.getDetails().toString();
        assertFalse(detailsString.contains("password"), "Leaked password keyword");
        assertFalse(detailsString.contains("supabase_admin"), "Leaked database user");
        assertFalse(detailsString.contains("supabase.co"), "Leaked database host");
    }

    @Test
    @DisplayName("Connection Acquisition Timeout: Bounds check and returns Status.DOWN with TIMEOUT")
    void testConnectionAcquisitionTimeout() {
        // Simulate a pool exhaustion or hanging network socket in getConnection()
        DataSource hangingDs = new StubDataSource(() -> {
            try {
                Thread.sleep(4000); // Exceeds the 3-second bounded timeout
            } catch (InterruptedException e) {
                // expected interruption on cancel
            }
            return null;
        });

        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(hangingDs, executor);
        long start = System.currentTimeMillis();
        Health health = indicator.health();
        long duration = System.currentTimeMillis() - start;

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("TIMEOUT", health.getDetails().get("status"));
        assertTrue(duration < 3900, "Health check must be bounded by ~3 seconds timeout, took " + duration + "ms");
    }

    @Test
    @DisplayName("Query Timeout: Returns Status.DOWN and sanitized UNAVAILABLE status")
    void testQueryTimeout() {
        Connection conn = createMockConnection(false, true);
        DataSource ds = new StubDataSource(() -> conn);
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(ds, executor);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("UNAVAILABLE", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Null Connection: Returns Status.DOWN and DISCONNECTED status")
    void testNullConnection() {
        DataSource ds = new StubDataSource(() -> null);
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(ds, executor);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DISCONNECTED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Null DataSource: Returns Status.DOWN and UNCONFIGURED status")
    void testNullDataSource() {
        DatabaseReadinessHealthIndicator indicatorWithoutDs = new DatabaseReadinessHealthIndicator(null, executor);
        Health health = indicatorWithoutDs.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("UNCONFIGURED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("Task Abort: Cancels active statement and closes connection on timeout")
    void testTaskAbortOnTimeout() {
        java.util.concurrent.atomic.AtomicBoolean statementCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean connectionClosed = new java.util.concurrent.atomic.AtomicBoolean(false);

        Statement stmtProxy = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("execute".equals(name)) {
                        try {
                            Thread.sleep(5000); // Hangs until timeout aborts
                        } catch (InterruptedException e) {
                            // expected interrupt
                        }
                        return true;
                    }
                    if ("cancel".equals(name)) {
                        statementCancelled.set(true);
                        return null;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    if ("setQueryTimeout".equals(name)) {
                        return null;
                    }
                    return null;
                }
        );

        Connection connProxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("createStatement".equals(name)) {
                        return stmtProxy;
                    }
                    if ("close".equals(name)) {
                        connectionClosed.set(true);
                        return null;
                    }
                    return null;
                }
        );

        DataSource ds = new StubDataSource(() -> connProxy);
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(ds, executor);

        long start = System.currentTimeMillis();
        Health health = indicator.health();
        long duration = System.currentTimeMillis() - start;

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("TIMEOUT", health.getDetails().get("status"));
        assertTrue(duration < 3900, "Health check must be bounded by ~3 seconds timeout, took " + duration + "ms");
        assertTrue(statementCancelled.get(), "Statement must be actively cancelled on timeout");
        assertTrue(connectionClosed.get(), "Connection must be actively closed on timeout");
    }

    @Test
    @DisplayName("Lifecycle Close: Closes executor cleanly via close()")
    void testLifecycleClose() {
        DatabaseReadinessHealthIndicator indicator = new DatabaseReadinessHealthIndicator(null);
        indicator.close();
        // Subsequent health check with closed executor
        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
    }
}
