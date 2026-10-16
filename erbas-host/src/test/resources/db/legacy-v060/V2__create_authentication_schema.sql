CREATE TABLE auth_user (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email TEXT NOT NULL UNIQUE CHECK (email <> ''),
    password_hash TEXT NOT NULL CHECK (password_hash <> ''),
    enabled BOOLEAN NOT NULL
);

CREATE TABLE auth_access_token (
    token_hash TEXT PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id BIGINT NOT NULL REFERENCES auth_user (id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_access_token_positive_lifetime CHECK (expires_at > created_at)
);

CREATE INDEX auth_access_token_user_id_idx ON auth_access_token (user_id);
CREATE INDEX auth_access_token_expires_at_idx ON auth_access_token (expires_at);
