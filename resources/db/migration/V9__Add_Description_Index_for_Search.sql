-- V9__Add_Description_Index_for_Search.sql
-- Adds GIN index on user_attributes.description for efficient keyword search

-- Create a GIN index for efficient text search on attribute descriptions
CREATE INDEX IF NOT EXISTS idx_user_attrs_description_gin 
ON user_attributes USING gin(description gin_trgm_ops);

-- Add a comment for documentation
COMMENT ON INDEX idx_user_attrs_description_gin IS 
    'GIN trigram index for fast keyword search on translated attribute descriptions';
