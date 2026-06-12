-- Initial database schema for Quarkus CRUD application
-- This migration creates all necessary tables and indexes for the application
-- All tables have BIGINT GENERATED ALWAYS AS IDENTITY as primary key (implicit)

-- ============================================================================
-- User Table
-- ============================================================================
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_lifecycle VARCHAR(50) NOT NULL DEFAULT 'created',
    current_profile_revision BIGINT,
    meta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Comments for documentation
COMMENT ON TABLE users IS 'Base user entity - handles user lifecycle and profile management';
COMMENT ON COLUMN users.account_lifecycle IS 'Account lifecycle state: created, provisioned, active, paused, deleted';
COMMENT ON COLUMN users.current_profile_revision IS 'Foreign key to the current profile revision for this user';
COMMENT ON COLUMN users.meta IS 'Flexible metadata (e.g., pause reason) stored as JSONB';

-- Indexes on users table
CREATE INDEX idx_users_lifecycle ON users(account_lifecycle);
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- ============================================================================
-- Authentication Providers Table
-- ============================================================================
CREATE TABLE authn_providers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    auth_method VARCHAR(50) NOT NULL,
    auth_identifier VARCHAR(255),
    external_subject VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authn_providers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE authn_providers IS 'Authentication provider information for users';
COMMENT ON COLUMN authn_providers.auth_method IS 'Authentication method: anonymous, oidc, etc.';
COMMENT ON COLUMN authn_providers.auth_identifier IS 'Internal authentication identifier (for anonymous)';
COMMENT ON COLUMN authn_providers.external_subject IS 'Subject identifier from external provider (for OIDC, etc.)';

-- Indexes on authn_providers table
CREATE INDEX idx_authn_providers_user_id ON authn_providers(user_id);
CREATE INDEX idx_authn_providers_auth_identifier ON authn_providers(auth_identifier);
CREATE INDEX idx_authn_providers_method_external ON authn_providers(auth_method, external_subject);

-- Unique constraint for anonymous auth (one anonymous auth per identifier)
CREATE UNIQUE INDEX idx_authn_providers_unique_anonymous 
    ON authn_providers(auth_identifier) 
    WHERE auth_method = 'anonymous' AND auth_identifier IS NOT NULL;

-- Unique constraint for external auth (one external subject per method)
CREATE UNIQUE INDEX idx_authn_providers_unique_external 
    ON authn_providers(auth_method, external_subject) 
    WHERE auth_method != 'anonymous' AND external_subject IS NOT NULL;

-- ============================================================================
-- User Profile Table
-- ============================================================================
CREATE TABLE user_profiles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    profile_data JSONB NOT NULL,
    revision_meta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE user_profiles IS 'User profile revisions - each record is immutable except revision_meta';
COMMENT ON COLUMN user_profiles.profile_data IS 'Profile card data stored as JSONB (immutable)';
COMMENT ON COLUMN user_profiles.revision_meta IS 'Metadata about this revision (mutable for administrative purposes)';

-- Indexes on user_profiles table
CREATE INDEX idx_user_profiles_user_id ON user_profiles(user_id);
CREATE INDEX idx_user_profiles_created_at ON user_profiles(created_at DESC);

-- Add foreign key constraint from users to user_profiles (for current_profile_revision)
ALTER TABLE users ADD CONSTRAINT fk_users_current_profile 
    FOREIGN KEY (current_profile_revision) REFERENCES user_profiles(id) ON DELETE SET NULL;

-- ============================================================================
-- Friendship Table
-- ============================================================================
CREATE TABLE friendships (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_friendships_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friendships_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE friendships IS 'Bidirectional m:n relationship for profile card exchange (both directions created automatically)';
COMMENT ON COLUMN friendships.sender_id IS 'User who sent their profile card';
COMMENT ON COLUMN friendships.recipient_id IS 'User who received the profile card';

-- Indexes on friendships table
CREATE INDEX idx_friendships_sender ON friendships(sender_id);
CREATE INDEX idx_friendships_recipient ON friendships(recipient_id);

-- Unique constraint: one friendship per sender-recipient pair
CREATE UNIQUE INDEX idx_friendships_unique_pair ON friendships(sender_id, recipient_id);

-- ============================================================================
-- Events Table (Quiz Events)
-- ============================================================================
CREATE TABLE events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    initiator_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'created',
    meta JSONB,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_events_initiator FOREIGN KEY (initiator_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE events IS 'Quiz event entity';
COMMENT ON COLUMN events.initiator_id IS 'User who initiated the event';
COMMENT ON COLUMN events.status IS 'Event status: created, active, ended, expired, deleted';
COMMENT ON COLUMN events.meta IS 'Flexible event metadata stored as JSONB';
COMMENT ON COLUMN events.expires_at IS 'Optional expiration timestamp for the event (null means no expiration)';

-- Indexes on events table
CREATE INDEX idx_events_initiator ON events(initiator_id);
CREATE INDEX idx_events_status ON events(status);
CREATE INDEX idx_events_expires_at ON events(expires_at);
CREATE INDEX idx_events_created_at ON events(created_at DESC);

-- ============================================================================
-- Event Invitation Codes Table
-- ============================================================================
CREATE TABLE event_invitation_codes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id BIGINT NOT NULL,
    invitation_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_codes_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE event_invitation_codes IS 'Event invitation codes - separate table for performance';
COMMENT ON COLUMN event_invitation_codes.invitation_code IS 'Unique invitation code for active events only';

-- Indexes on event_invitation_codes table
CREATE INDEX idx_event_codes_event_id ON event_invitation_codes(event_id);
CREATE INDEX idx_event_codes_code ON event_invitation_codes(invitation_code);

-- Unique constraint: invitation code must be unique among non-expired/non-deleted events
-- Since PostgreSQL doesn't support subqueries in partial index predicates,
-- we enforce uniqueness at the application level with exclusive locking.
-- This index supports fast lookups by invitation code:
CREATE UNIQUE INDEX idx_event_codes_event_code ON event_invitation_codes(event_id, invitation_code);

-- ============================================================================
-- Event Attendees Table
-- ============================================================================
CREATE TABLE event_attendees (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id BIGINT NOT NULL,
    attendee_user_id BIGINT NOT NULL,
    meta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_attendees_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_attendees_user FOREIGN KEY (attendee_user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comments for documentation
COMMENT ON TABLE event_attendees IS 'Event participants/attendees';
COMMENT ON COLUMN event_attendees.attendee_user_id IS 'User attending the event';
COMMENT ON COLUMN event_attendees.meta IS 'Flexible attendee metadata stored as JSONB';

-- Indexes on event_attendees table
CREATE INDEX idx_event_attendees_event ON event_attendees(event_id);
CREATE INDEX idx_event_attendees_user ON event_attendees(attendee_user_id);
CREATE INDEX idx_event_attendees_created_at ON event_attendees(created_at DESC);

-- Unique constraint: one attendance record per user per event
CREATE UNIQUE INDEX idx_event_attendees_unique_pair ON event_attendees(event_id, attendee_user_id);
