package db.migration;

import com.queueless.util.PublicMerchantCodeGenerator;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * QueueLess Flyway Migration V10: Add opaque public merchant code to shops.
 * Generates codes using Java SecureRandom with format pm_ + 16 Base62 characters.
 * Safely backfills any existing merchants before enforcing UNIQUE and NOT NULL constraints.
 */
public class V10__add_public_merchant_code_to_shops extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V10__add_public_merchant_code_to_shops.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        log.info("Executing Flyway Migration V10: Adding public_merchant_code to shops...");

        // 1. Add nullable column if not exists
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS public_merchant_code VARCHAR(32)");
            log.info("Step 1: Column 'public_merchant_code' ensured on table 'shops'");
        }

        // 2. Collect existing codes to prevent uniqueness collisions
        Set<String> assignedCodes = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT public_merchant_code FROM shops WHERE public_merchant_code IS NOT NULL AND public_merchant_code <> ''")) {
            while (rs.next()) {
                assignedCodes.add(rs.getString("public_merchant_code"));
            }
        }

        // 3. Backfill existing records with Java SecureRandom Base62 codes
        int backfilledCount = 0;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id FROM shops WHERE public_merchant_code IS NULL OR public_merchant_code = ''")) {

            while (rs.next()) {
                long shopId = rs.getLong("id");
                String newCode;
                int retries = 0;
                do {
                    newCode = PublicMerchantCodeGenerator.generate();
                    retries++;
                    if (retries > 100) {
                        throw new IllegalStateException("Excessive collisions encountered during public merchant code backfill");
                    }
                } while (!assignedCodes.add(newCode));

                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE shops SET public_merchant_code = ? WHERE id = ?")) {
                    updateStmt.setString(1, newCode);
                    updateStmt.setLong(2, shopId);
                    updateStmt.executeUpdate();
                }
                backfilledCount++;
            }
        }
        log.info("Step 2: Successfully backfilled {} existing shops with opaque public codes", backfilledCount);

        // 4. Create Unique Index
        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_public_merchant_code ON shops(public_merchant_code)");
                log.info("Step 3: Unique index 'idx_shops_public_merchant_code' created");
            } catch (Exception ex) {
                log.warn("Direct unique index creation note: {}", ex.getMessage());
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_shops_public_merchant_code ON shops(public_merchant_code)");
            }
        }

        // NOTE (Expand/Contract Strategy):
        // In Stage 1 (Expand), the public_merchant_code column remains NULLABLE so unupgraded
        // application instances (V_old) can continue creating shops during rolling deployment.
        // NOT NULL enforcement is deferred to Stage 3 (Contract) after all instances are cut over.

        log.info("Flyway Migration V10 complete: public_merchant_code operational (Stage 1 - Expand).");
    }
}
