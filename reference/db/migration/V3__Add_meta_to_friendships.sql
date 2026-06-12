-- Add meta column to friendships table
-- This allows storing flexible metadata for each friendship exchange

ALTER TABLE friendships ADD COLUMN meta JSONB;

COMMENT ON COLUMN friendships.meta IS 'Flexible metadata captured during profile card exchange';
