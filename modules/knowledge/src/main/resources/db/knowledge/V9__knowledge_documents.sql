CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY,
    owner_module TEXT NOT NULL CHECK (btrim(owner_module) <> ''),
    storage_scope UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_document_revisions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_documents(id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    provenance TEXT NOT NULL CHECK (btrim(provenance) <> ''),
    created_by TEXT NOT NULL CHECK (btrim(created_by) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    required_permissions TEXT[] NOT NULL CHECK (cardinality(required_permissions) > 0),
    module_policy_required BOOLEAN NOT NULL,
    content_type TEXT NOT NULL CHECK (content_type IN ('text/plain', 'text/markdown')),
    original_object UUID NOT NULL UNIQUE,
    original_size BIGINT NOT NULL CHECK (original_size >= 0),
    normalized_object UUID NOT NULL UNIQUE,
    normalized_size BIGINT NOT NULL CHECK (normalized_size >= 0),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    normalized_sha256 TEXT NOT NULL CHECK (normalized_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (original_object <> normalized_object),
    UNIQUE (document_id, revision_number)
);

-- Revisions are append-only, including when accessed outside the Java adapter.
CREATE FUNCTION knowledge_reject_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Document revisions are immutable';
END;
$$;
CREATE TRIGGER knowledge_immutable_revisions
    BEFORE UPDATE OR DELETE ON knowledge_document_revisions
    FOR EACH ROW EXECUTE FUNCTION knowledge_reject_revision_mutation();
