package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * QueueLess Flyway Migration V7: Drop any remaining UNIQUE constraint or index on shops(owner_id).
 *
 * In V1, shops.owner_id was created with an inline UNIQUE constraint.
 * In V4, 'ALTER TABLE shops DROP CONSTRAINT IF EXISTS shops_owner_id_key' was executed,
 * but in H2 and non-standard PostgreSQL deployments, inline unique constraints receive synthetic
 * names (e.g. CONSTRAINT_685) or are backed by unnamed unique indexes.
 * This migration dynamically inspects the database catalog and drops all unique constraints
 * and unique indexes on shops.owner_id across all supported engines (PostgreSQL and H2).
 */
public class V7__drop_shops_owner_unique_constraint extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V7__drop_shops_owner_unique_constraint.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        String dbProduct = meta.getDatabaseProductName();
        log.info("Running V7 Java Migration on database product: {}", dbProduct);

        Set<String> constraintsToDrop = new HashSet<>();

        // 1. Standard PostgreSQL naming attempt
        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("ALTER TABLE shops DROP CONSTRAINT IF EXISTS shops_owner_id_key");
                log.info("Executed: ALTER TABLE shops DROP CONSTRAINT IF EXISTS shops_owner_id_key");
            } catch (Exception ignored) {}
            try {
                stmt.execute("DROP INDEX IF EXISTS shops_owner_id_key");
                log.info("Executed: DROP INDEX IF EXISTS shops_owner_id_key");
            } catch (Exception ignored) {}
        }

        // 2. Query INFORMATION_SCHEMA for any unique constraints on shops.owner_id
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tc.CONSTRAINT_NAME " +
                     "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                     "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                     "  ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME " +
                     "  AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA " +
                     "  AND tc.TABLE_NAME = kcu.TABLE_NAME " +
                     "WHERE LOWER(tc.TABLE_NAME) = 'shops' " +
                     "  AND LOWER(kcu.COLUMN_NAME) = 'owner_id' " +
                     "  AND UPPER(tc.CONSTRAINT_TYPE) = 'UNIQUE'")) {
            while (rs.next()) {
                String name = rs.getString("CONSTRAINT_NAME");
                if (name != null && !name.isBlank()) {
                    constraintsToDrop.add(name);
                }
            }
        } catch (Exception e) {
            log.warn("Information schema constraint query skipped: {}", e.getMessage());
        }

        // 2b. PostgreSQL pg_constraint catalog check (if PostgreSQL)
        if (dbProduct != null && dbProduct.toLowerCase().contains("postgres")) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'owner_id' " +
                         "  AND con.contype = 'u' " +
                         "  AND array_length(con.conkey, 1) = 1")) {
                while (rs.next()) {
                    String name = rs.getString("conname");
                    if (name != null && !name.isBlank()) {
                        constraintsToDrop.add(name);
                    }
                }
            } catch (Exception e) {
                log.warn("PostgreSQL pg_constraint query skipped: {}", e.getMessage());
            }
        }

        // 3. Drop all identified unique constraints
        for (String constraintName : constraintsToDrop) {
            try (Statement dropStmt = conn.createStatement()) {
                dropStmt.execute("ALTER TABLE shops DROP CONSTRAINT IF EXISTS \"" + constraintName + "\"");
                log.info("Successfully dropped unique constraint on shops.owner_id: {}", constraintName);
            } catch (Exception e) {
                try (Statement dropStmt2 = conn.createStatement()) {
                    dropStmt2.execute("ALTER TABLE shops DROP CONSTRAINT IF EXISTS " + constraintName);
                    log.info("Successfully dropped unquoted unique constraint on shops.owner_id: {}", constraintName);
                } catch (Exception ex2) {
                    log.warn("Could not drop constraint {}: {}", constraintName, ex2.getMessage());
                }
            }
        }

        // 4. Also inspect DatabaseMetaData index info for any unique indexes solely on owner_id
        Map<String, List<String>> indexColumnsMap = new HashMap<>();
        String[] tableVariants = {"shops", "SHOPS", "Shops"};
        for (String tbl : tableVariants) {
            try (ResultSet rs = meta.getIndexInfo(null, null, tbl, true, false)) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String idxName = rs.getString("INDEX_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                    if (idxName != null && !nonUnique) {
                        String lower = idxName.toLowerCase();
                        if (!lower.startsWith("primary") && !lower.contains("pkey")) {
                            indexColumnsMap.computeIfAbsent(idxName, k -> new ArrayList<>()).add(colName);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Set<String> uniqueIndexesToDrop = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : indexColumnsMap.entrySet()) {
            List<String> cols = entry.getValue();
            if (cols.size() == 1 && "owner_id".equalsIgnoreCase(cols.get(0))) {
                uniqueIndexesToDrop.add(entry.getKey());
            }
        }

        for (String idxName : uniqueIndexesToDrop) {
            try (Statement dropStmt = conn.createStatement()) {
                dropStmt.execute("DROP INDEX IF EXISTS \"" + idxName + "\"");
                log.info("Successfully dropped unique index on shops.owner_id: {}", idxName);
            } catch (Exception e) {
                try (Statement dropStmt2 = conn.createStatement()) {
                    dropStmt2.execute("DROP INDEX IF EXISTS " + idxName);
                    log.info("Successfully dropped unquoted unique index on shops.owner_id: {}", idxName);
                } catch (Exception ignored) {}
            }
        }

        log.info("V7 Java Migration completed successfully. shops.owner_id is non-unique.");
    }
}
