-- =========================================================================
-- QueueLess Database Migration: V11__add_business_type_to_shops.sql
-- Stage: STAGE 1 — EXPAND
-- Objective: Add nullable business_type column to shops table.
-- Backward Compatibility: Column is NULLABLE so existing shop records remain valid.
-- Validation: Enforced as required at the application/DTO level for new registrations.
-- =========================================================================

ALTER TABLE shops ADD COLUMN IF NOT EXISTS business_type VARCHAR(100);
