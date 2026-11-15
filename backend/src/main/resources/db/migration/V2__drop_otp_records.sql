-- Flyway Migration V2: Remove obsolete OTP table
-- The OTP verification concept has been completely removed in favor of direct Mobile + PIN authentication.

DROP TABLE IF EXISTS otp_records CASCADE;
