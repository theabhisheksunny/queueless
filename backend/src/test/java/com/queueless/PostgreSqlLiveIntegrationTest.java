package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PostgreSqlLiveIntegrationTest {

    @Test
    void testEmbeddedPostgresStartupAndVersion() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.start()) {
            DataSource ds = pg.getPostgresDatabase();
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertTrue(rs.next());
                String version = rs.getString(1);
                System.out.println(">>> RUNNING ON REAL POSTGRESQL: " + version);
                assertNotNull(version);
                assertTrue(version.toLowerCase().contains("postgresql"));
            }
        }
    }
}
