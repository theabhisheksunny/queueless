package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.PgBinaryResolver;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

public class PostgreSql16ActualInstanceTest {

    @Test
    void testStartActualPostgreSql16() throws Exception {
        File jarFile = new File(System.getProperty("user.home") +
                "/.m2/repository/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/16.4.0/embedded-postgres-binaries-windows-amd64-16.4.0.jar");
        assertTrue(jarFile.exists(), "PG 16 binary jar must exist at " + jarFile.getAbsolutePath());

        PgBinaryResolver pg16Resolver = (system, machine) -> {
            JarFile jar = new JarFile(jarFile);
            JarEntry entry = jar.getJarEntry("postgres-windows-x86_64.txz");
            if (entry == null) {
                throw new IllegalStateException("postgres-windows-x86_64.txz not found in " + jarFile);
            }
            return jar.getInputStream(entry);
        };

        try (EmbeddedPostgres pg16 = EmbeddedPostgres.builder()
                .setPgBinaryResolver(pg16Resolver)
                .start()) {

            DataSource ds = pg16.getPostgresDatabase();
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {

                try (ResultSet rs = stmt.executeQuery("SELECT version()")) {
                    assertTrue(rs.next());
                    String version = rs.getString(1);
                    System.out.println("ACTUAL_PG_VERSION: " + version);
                    assertTrue(version.contains("PostgreSQL 16"), "Must be PostgreSQL 16! Actual: " + version);
                }

                try (ResultSet rs = stmt.executeQuery("SHOW server_version")) {
                    assertTrue(rs.next());
                    String serverVersion = rs.getString(1);
                    System.out.println("SERVER_VERSION: " + serverVersion);
                    assertTrue(serverVersion.startsWith("16."), "Server version must start with 16. Actual: " + serverVersion);
                }

                try (ResultSet rs = stmt.executeQuery("""
                    SELECT column_name, data_type
                    FROM information_schema.columns
                    WHERE table_name = 'pg_auth_members'
                    ORDER BY ordinal_position
                    """)) {
                    System.out.println("PG_AUTH_MEMBERS COLUMNS:");
                    boolean foundSetOption = false;
                    while (rs.next()) {
                        String col = rs.getString("column_name");
                        String type = rs.getString("data_type");
                        System.out.println(" - " + col + " (" + type + ")");
                        if ("set_option".equalsIgnoreCase(col)) {
                            foundSetOption = true;
                        }
                    }
                    assertTrue(foundSetOption, "set_option column MUST exist in pg_auth_members on PostgreSQL 16!");
                }
            }
        }
    }
}
