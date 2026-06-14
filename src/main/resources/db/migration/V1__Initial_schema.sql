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
    sysmeta JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE refresh_tokens IS 'Opaque refresh token lifecycle records. Raw tokens are never stored.';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of the raw opaque refresh token';
COMMENT ON COLUMN refresh_tokens.token_family IS 'Logical family identifier used to revoke rotated-token replay chains';
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
    enabled_tools JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_speed_dials_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE speed_dials IS 'User-owned speed dial presets';
COMMENT ON COLUMN speed_dials.speed_dial_id IS 'Stable per-user speed dial identifier used by the client API';
COMMENT ON COLUMN speed_dials.enabled_tools IS 'Per-tool enable/disable overrides keyed by tool name';

CREATE UNIQUE INDEX idx_speed_dials_unique_user_speed_dial_id
    ON speed_dials(user_id, speed_dial_id);
CREATE INDEX idx_speed_dials_user_id ON speed_dials(user_id);
CREATE INDEX idx_speed_dials_created_at ON speed_dials(created_at DESC);

-- ============================================================================
-- Persisted Virtual Filesystem Files
-- Directories remain implicit and are derived from path prefixes.
-- ============================================================================
CREATE TABLE vfs_files (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    path VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vfs_files_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

COMMENT ON TABLE vfs_files IS 'User-owned persisted virtual filesystem files';
COMMENT ON COLUMN vfs_files.path IS 'Absolute virtual filesystem path; directories are implicit';
COMMENT ON COLUMN vfs_files.content IS 'Persisted file content';

CREATE UNIQUE INDEX idx_vfs_files_unique_user_path
    ON vfs_files(user_id, path);
CREATE INDEX idx_vfs_files_user_id ON vfs_files(user_id);
