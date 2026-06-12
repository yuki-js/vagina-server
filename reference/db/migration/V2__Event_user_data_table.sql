-- Migration to add event_user_data table for storing per-event, per-user data with revisions
-- Similar to user_profiles but scoped to an event
-- 
-- Design note: This table stores multiple revisions per event_id/user_id pair by design.
-- The latest revision is retrieved using ORDER BY created_at DESC LIMIT 1.
-- This allows tracking history of user data changes within an event.

-- ============================================================================
-- Remove unused current_profile_revision column from users table
-- ============================================================================
-- Since we now always lookup the latest profile revision using 
-- ORDER BY created_at DESC LIMIT 1, this cached pointer column is no longer needed.
ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_current_profile;
ALTER TABLE users DROP COLUMN IF EXISTS current_profile_revision;

-- ============================================================================
-- Event User Data Table
-- ============================================================================
CREATE TABLE event_user_data (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_data JSONB NOT NULL,
    revision_meta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_user_data_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_user_data_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE event_user_data IS 'Event-specific user data revisions - each record is immutable except revision_meta';
COMMENT ON COLUMN event_user_data.event_id IS 'Event this data belongs to';
COMMENT ON COLUMN event_user_data.user_id IS 'User this data belongs to';
COMMENT ON COLUMN event_user_data.user_data IS 'User data stored as JSONB (immutable)';
COMMENT ON COLUMN event_user_data.revision_meta IS 'Metadata about this revision (mutable for administrative purposes)';

-- Indexes on event_user_data table
CREATE INDEX idx_event_user_data_event_id ON event_user_data(event_id);
CREATE INDEX idx_event_user_data_user_id ON event_user_data(user_id);
CREATE INDEX idx_event_user_data_event_user ON event_user_data(event_id, user_id);
CREATE INDEX idx_event_user_data_created_at ON event_user_data(created_at DESC);
