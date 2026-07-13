-- Initial schema for Vagina Server.
-- Pre-initial-release policy: all schema changes are folded into this V1 migration.

-- ============================================================================
-- Users
-- Stable identity root. Keep this table small and avoid provider-specific fields.
-- ============================================================================
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_lifecycle VARCHAR(50) NOT NULL DEFAULT 'created',
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE users IS 'Stable identity root for application users';
COMMENT ON COLUMN users.account_lifecycle IS 'Account lifecycle state: created, active, paused, deleted';
COMMENT ON COLUMN users.usermeta IS 'User-editable arbitrary metadata';
COMMENT ON COLUMN users.sysmeta IS 'System/admin-only auxiliary metadata';

CREATE INDEX idx_users_lifecycle ON users(account_lifecycle);
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- ============================================================================
-- Entitlements
-- Typed feature, plan, and privilege grants used for authorization decisions.
-- ============================================================================
CREATE TABLE entitlement_definitions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entitlement_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE entitlement_definitions IS 'Catalog of server-known entitlement keys used for feature, plan, and privilege checks';
COMMENT ON COLUMN entitlement_definitions.entitlement_key IS 'Stable entitlement key such as premium.voice or admin.support';
COMMENT ON COLUMN entitlement_definitions.enabled IS 'Whether new and existing grants for this entitlement are effective';
COMMENT ON COLUMN entitlement_definitions.sysmeta IS 'System/admin-only auxiliary metadata for entitlement catalog management';

CREATE UNIQUE INDEX idx_entitlement_definitions_key
    ON entitlement_definitions(entitlement_key);
CREATE INDEX idx_entitlement_definitions_enabled
    ON entitlement_definitions(enabled);

CREATE TABLE user_entitlement_grants (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    entitlement_id BIGINT NOT NULL,
    grant_source VARCHAR(50) NOT NULL,
    valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    grant_reason TEXT,
    revoke_reason TEXT,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_entitlement_grants_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_entitlement_grants_entitlement
        FOREIGN KEY (entitlement_id) REFERENCES entitlement_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT chk_user_entitlement_grants_time_window
        CHECK (expires_at IS NULL OR expires_at > valid_from)
);

COMMENT ON TABLE user_entitlement_grants IS 'Per-user typed entitlement grants; effective grants have no revocation and are within their validity window';
COMMENT ON COLUMN user_entitlement_grants.grant_source IS 'Grant source: manual, subscription, system, migration';
COMMENT ON COLUMN user_entitlement_grants.valid_from IS 'First timestamp when this grant can become effective';
COMMENT ON COLUMN user_entitlement_grants.expires_at IS 'Timestamp after which this grant is no longer effective';
COMMENT ON COLUMN user_entitlement_grants.revoked_at IS 'Revocation timestamp; non-null grants are no longer effective';
COMMENT ON COLUMN user_entitlement_grants.usermeta IS 'User-editable arbitrary metadata for this entitlement grant';
COMMENT ON COLUMN user_entitlement_grants.sysmeta IS 'System/admin-only auxiliary metadata such as external billing identifiers';

CREATE INDEX idx_user_entitlement_grants_user_id
    ON user_entitlement_grants(user_id);
CREATE INDEX idx_user_entitlement_grants_entitlement_id
    ON user_entitlement_grants(entitlement_id);
CREATE INDEX idx_user_entitlement_grants_active_lookup
    ON user_entitlement_grants(user_id, valid_from, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_user_entitlement_grants_user_entitlement
    ON user_entitlement_grants(user_id, entitlement_id);

-- ============================================================================
-- Authentication Providers
-- OIDC provider link table. Normal provider-derived user info is stored in typed columns.
-- ============================================================================
CREATE TABLE authn_providers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    auth_method VARCHAR(50) NOT NULL,
    provider_key VARCHAR(50) NOT NULL,
    auth_identifier VARCHAR(255),
    external_subject VARCHAR(255) NOT NULL,
    provider_login VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url TEXT,
    email VARCHAR(255),
    email_verified BOOLEAN,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authn_providers_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE authn_providers IS 'OIDC provider identities linked to users';
COMMENT ON COLUMN authn_providers.auth_method IS 'Authentication method: oidc';
COMMENT ON COLUMN authn_providers.provider_key IS 'Provider key: harigata, github, google, apple, twitter';
COMMENT ON COLUMN authn_providers.auth_identifier IS 'Internal stable identifier for provider-link bookkeeping if needed';
COMMENT ON COLUMN authn_providers.external_subject IS 'Stable subject identifier returned by the external OIDC provider';
COMMENT ON COLUMN authn_providers.provider_login IS 'Provider-specific login or handle used for display and diagnostics';
COMMENT ON COLUMN authn_providers.display_name IS 'Provider-derived display name used by product-facing projections';
COMMENT ON COLUMN authn_providers.avatar_url IS 'Provider-derived avatar URL used by product-facing projections';
COMMENT ON COLUMN authn_providers.email IS 'Provider-derived email when available';
COMMENT ON COLUMN authn_providers.email_verified IS 'Whether the provider considers the email verified';
COMMENT ON COLUMN authn_providers.usermeta IS 'User-editable arbitrary metadata for this provider link';
COMMENT ON COLUMN authn_providers.sysmeta IS 'System/admin-only auxiliary metadata such as raw provider payloads or sync diagnostics';

CREATE INDEX idx_authn_providers_user_id ON authn_providers(user_id);
CREATE INDEX idx_authn_providers_auth_method ON authn_providers(auth_method);
CREATE INDEX idx_authn_providers_provider_key ON authn_providers(provider_key);
CREATE INDEX idx_authn_providers_email ON authn_providers(email);

CREATE UNIQUE INDEX idx_authn_providers_unique_external
    ON authn_providers(provider_key, external_subject);

-- ============================================================================
-- Refresh Tokens
-- Credential lifecycle records. These are not revision tables.
-- ============================================================================
CREATE TABLE refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    token_family VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP,
    revoked_at TIMESTAMP,
    last_used_at TIMESTAMP,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE refresh_tokens IS 'Opaque refresh token lifecycle records. Raw tokens are never stored.';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of the raw opaque refresh token';
COMMENT ON COLUMN refresh_tokens.token_family IS 'Logical family identifier used to revoke rotated-token replay chains';
COMMENT ON COLUMN refresh_tokens.usermeta IS 'User-editable arbitrary metadata for this refresh token record';
COMMENT ON COLUMN refresh_tokens.sysmeta IS 'System/admin-only auxiliary metadata such as user agent, IP address, or revoke reason';

CREATE UNIQUE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(token_family);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);

-- ============================================================================
-- OAuth Login Attempts
-- Persisted short-lived OIDC transaction state. This is not a classic server session.
-- ============================================================================
CREATE TABLE oauth_login_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state_hash VARCHAR(128) NOT NULL,
    auth_method VARCHAR(50) NOT NULL,
    provider_key VARCHAR(50) NOT NULL,
    client_type VARCHAR(50) NOT NULL,
    redirect_uri TEXT NOT NULL,
    code_challenge VARCHAR(255) NOT NULL,
    code_challenge_method VARCHAR(20) NOT NULL DEFAULT 'S256',
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE oauth_login_attempts IS 'Short-lived persisted OIDC transaction state. Avoids server-memory session dependence.';
COMMENT ON COLUMN oauth_login_attempts.state_hash IS 'SHA-256 hash of the raw OAuth state value';
COMMENT ON COLUMN oauth_login_attempts.auth_method IS 'Authentication method: oidc';
COMMENT ON COLUMN oauth_login_attempts.provider_key IS 'Provider key: harigata, github, google, apple, twitter';
COMMENT ON COLUMN oauth_login_attempts.client_type IS 'Client type: web, mobile, desktop';
COMMENT ON COLUMN oauth_login_attempts.sysmeta IS 'System/admin-only auxiliary metadata such as request diagnostics';

CREATE UNIQUE INDEX idx_oauth_login_attempts_state_hash ON oauth_login_attempts(state_hash);
CREATE INDEX idx_oauth_login_attempts_expires_at ON oauth_login_attempts(expires_at);

-- ============================================================================
-- Speed Dials
-- User-owned voice agent presets. The external/public identifier is speed_dial_id.
-- ============================================================================
CREATE TABLE speed_dials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    speed_dial_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    system_prompt TEXT NOT NULL,
    description TEXT,
    icon_emoji VARCHAR(16),
    voice VARCHAR(64) NOT NULL,
    voice_agent_id VARCHAR(128) NOT NULL DEFAULT 'voice-agent-prod',
    tool_choice_required BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_tools JSONB NOT NULL DEFAULT '{}'::jsonb,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_speed_dials_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE speed_dials IS 'User-owned speed dial presets';
COMMENT ON COLUMN speed_dials.speed_dial_id IS 'Stable per-user speed dial identifier used by the client API';
COMMENT ON COLUMN speed_dials.voice_agent_id IS 'Server registry voice-agent id selected for this speed dial';
COMMENT ON COLUMN speed_dials.tool_choice_required IS 'Whether model tool choice should require a tool call when tools are available';
COMMENT ON COLUMN speed_dials.enabled_tools IS 'Per-tool enable/disable overrides keyed by tool name';
COMMENT ON COLUMN speed_dials.usermeta IS 'User-editable arbitrary metadata for this speed dial';
COMMENT ON COLUMN speed_dials.sysmeta IS 'System/admin-only auxiliary metadata for this speed dial';

CREATE UNIQUE INDEX idx_speed_dials_unique_user_speed_dial_id
    ON speed_dials(user_id, speed_dial_id);
CREATE INDEX idx_speed_dials_user_id ON speed_dials(user_id);
CREATE INDEX idx_speed_dials_created_at ON speed_dials(created_at DESC);

-- ============================================================================
-- Text Agents
-- User-owned text agent definitions. The external/public identifier is text_agent_id.
-- Provider, base URL, API key, self-hosted, and hosted transport details are intentionally
-- not persisted in this public definition table.
-- ============================================================================
CREATE TABLE text_agents (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    text_agent_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    prompt TEXT NOT NULL,
    description TEXT,
    text_model_id VARCHAR(128) NOT NULL DEFAULT 'text-agent-prod',
    enabled_tools JSONB NOT NULL DEFAULT '{}'::jsonb,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_text_agents_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE text_agents IS 'User-owned text agent definitions';
COMMENT ON COLUMN text_agents.text_agent_id IS 'Stable per-user text agent identifier used by the client API';
COMMENT ON COLUMN text_agents.text_model_id IS 'Server registry text-agent model preset id selected for this text agent';
COMMENT ON COLUMN text_agents.enabled_tools IS 'Per-tool enable/disable overrides keyed by tool name';
COMMENT ON COLUMN text_agents.usermeta IS 'User-editable arbitrary metadata for this text agent';
COMMENT ON COLUMN text_agents.sysmeta IS 'System/admin-only auxiliary metadata for this text agent';

CREATE UNIQUE INDEX idx_text_agents_unique_user_text_agent_id
    ON text_agents(user_id, text_agent_id);
CREATE INDEX idx_text_agents_user_id ON text_agents(user_id);
CREATE INDEX idx_text_agents_created_at ON text_agents(created_at DESC);

-- ============================================================================
-- Call Sessions
-- Terminal call history saved after realtime sessions end. The external/public
-- identifier is call_session_id; VHRP identifiers remain internal.
-- TODO: Add Speed Dial internal revision support when Speed Dial revisions exist.
-- TODO: Preserve the revision that produced a call session without exposing it as
-- TODO: a public API field unless product requirements later require that.
-- ============================================================================
CREATE TABLE call_sessions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    call_session_id UUID NOT NULL,
    vhrp_session_id VARCHAR(128) NOT NULL,
    vhrp_thread_id VARCHAR(128),
    speed_dial_id VARCHAR(128),
    voice_agent_id VARCHAR(128) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    thread_blob_key VARCHAR(512) NOT NULL,
    deleted_at TIMESTAMP,
    usermeta JSONB,
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_call_sessions_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE call_sessions IS 'User-owned terminal realtime call sessions saved for session history';
COMMENT ON COLUMN call_sessions.call_session_id IS 'Public UUID used by the client API for a saved call session';
COMMENT ON COLUMN call_sessions.vhrp_session_id IS 'Internal VHRP session identifier used for idempotent terminal save';
COMMENT ON COLUMN call_sessions.vhrp_thread_id IS 'Internal VHRP thread identifier associated with the saved realtime thread';
COMMENT ON COLUMN call_sessions.speed_dial_id IS 'Public speed dial identifier selected for the call when available; TODO: pair with an internal Speed Dial revision after revision support exists';
COMMENT ON COLUMN call_sessions.voice_agent_id IS 'Server registry voice-agent id selected for this call session';
COMMENT ON COLUMN call_sessions.started_at IS 'Call start timestamp used for session-history ordering';
COMMENT ON COLUMN call_sessions.ended_at IS 'Call end timestamp recorded when the terminal session is saved';
COMMENT ON COLUMN call_sessions.thread_blob_key IS 'Object-storage key for the saved realtime thread JSON. Raw audio chunks are not persisted.';
COMMENT ON COLUMN call_sessions.deleted_at IS 'Soft-delete timestamp. Non-null rows are hidden from user session-history APIs.';
COMMENT ON COLUMN call_sessions.usermeta IS 'User-editable arbitrary metadata for this call session';
COMMENT ON COLUMN call_sessions.sysmeta IS 'System/admin-only auxiliary metadata for this call session';

CREATE UNIQUE INDEX idx_call_sessions_call_session_id
    ON call_sessions(call_session_id);
CREATE UNIQUE INDEX idx_call_sessions_vhrp_session_id
    ON call_sessions(vhrp_session_id);
CREATE INDEX idx_call_sessions_user_started_active
    ON call_sessions(user_id, started_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_call_sessions_user_deleted_at
    ON call_sessions(user_id, deleted_at);

-- ============================================================================
-- VFS Storage
-- VFS lives as one stable snapshot blob per user in object storage.
-- No relational per-file table is kept in the greenfield schema.
-- ============================================================================
