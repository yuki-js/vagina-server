-- Migration to split 'meta' into 'usermeta' and 'sysmeta'
-- This migration renames existing meta columns to usermeta and adds sysmeta columns
-- for all relevant tables in the system.
--
-- Background:
-- - usermeta: User-editable metadata that record owners/participants can read and write
-- - sysmeta: System/admin-only metadata for programmatic or administrative purposes
-- - Both columns store JSONB data and do not require indexing

-- ============================================================================
-- Step 1: Rename existing 'meta' columns to 'usermeta'
-- ============================================================================

ALTER TABLE users RENAME COLUMN meta TO usermeta;
ALTER TABLE events RENAME COLUMN meta TO usermeta;
ALTER TABLE event_attendees RENAME COLUMN meta TO usermeta;
ALTER TABLE friendships RENAME COLUMN meta TO usermeta;

-- ============================================================================
-- Step 2: Add 'sysmeta' column to tables that had 'meta'
-- ============================================================================

ALTER TABLE users ADD COLUMN sysmeta JSONB;
ALTER TABLE events ADD COLUMN sysmeta JSONB;
ALTER TABLE event_attendees ADD COLUMN sysmeta JSONB;
ALTER TABLE friendships ADD COLUMN sysmeta JSONB;

-- ============================================================================
-- Step 3: Rename 'revision_meta' to 'sysmeta' in revision tables
-- ============================================================================

ALTER TABLE user_profiles RENAME COLUMN revision_meta TO sysmeta;
ALTER TABLE event_user_data RENAME COLUMN revision_meta TO sysmeta;

-- ============================================================================
-- Step 4: Add both 'usermeta' and 'sysmeta' to tables without metadata
-- (Only if the tables exist in this database schema)
-- ============================================================================

-- Note: rooms, authn_providers, and event_invitation_codes tables exist in schema
-- but rooms table may not exist in all deployments yet, so we skip it for now.
-- When the rooms table is added via a future migration, it should include
-- usermeta and sysmeta columns from the start.

ALTER TABLE authn_providers ADD COLUMN usermeta JSONB;
ALTER TABLE authn_providers ADD COLUMN sysmeta JSONB;

ALTER TABLE event_invitation_codes ADD COLUMN usermeta JSONB;
ALTER TABLE event_invitation_codes ADD COLUMN sysmeta JSONB;

-- ============================================================================
-- Step 5: Add 'usermeta' to revision tables (they already have sysmeta from step 3)
-- ============================================================================

ALTER TABLE user_profiles ADD COLUMN usermeta JSONB;
ALTER TABLE event_user_data ADD COLUMN usermeta JSONB;

-- ============================================================================
-- Update column comments for documentation
-- ============================================================================

-- Users table
COMMENT ON COLUMN users.usermeta IS 'User-editable metadata for this user account';
COMMENT ON COLUMN users.sysmeta IS 'System/admin-only metadata (e.g., pause reason, internal flags)';

-- Events table
COMMENT ON COLUMN events.usermeta IS 'User-editable event metadata';
COMMENT ON COLUMN events.sysmeta IS 'System/admin-only event metadata';

-- Event attendees table
COMMENT ON COLUMN event_attendees.usermeta IS 'User-editable attendee metadata';
COMMENT ON COLUMN event_attendees.sysmeta IS 'System/admin-only attendee metadata';

-- Friendships table
COMMENT ON COLUMN friendships.usermeta IS 'User-editable metadata captured during profile card exchange';
COMMENT ON COLUMN friendships.sysmeta IS 'System/admin-only friendship metadata';

-- User profiles table
COMMENT ON COLUMN user_profiles.usermeta IS 'User-editable metadata for this profile revision';
COMMENT ON COLUMN user_profiles.sysmeta IS 'System/admin-only metadata about this revision (formerly revision_meta)';

-- Event user data table
COMMENT ON COLUMN event_user_data.usermeta IS 'User-editable metadata for this event user data revision';
COMMENT ON COLUMN event_user_data.sysmeta IS 'System/admin-only metadata about this revision (formerly revision_meta)';

-- Authentication providers table
COMMENT ON COLUMN authn_providers.usermeta IS 'User-editable auth provider metadata';
COMMENT ON COLUMN authn_providers.sysmeta IS 'System/admin-only auth provider metadata';

-- Event invitation codes table
COMMENT ON COLUMN event_invitation_codes.usermeta IS 'User-editable invitation code metadata';
COMMENT ON COLUMN event_invitation_codes.sysmeta IS 'System/admin-only invitation code metadata';
