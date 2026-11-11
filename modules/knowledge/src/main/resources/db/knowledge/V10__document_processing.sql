-- V9 is immutable. Existing revisions retain the original text normalization recipe.
ALTER TABLE knowledge_document_revisions
    DROP CONSTRAINT knowledge_document_revisions_content_type_check,
    ADD CONSTRAINT knowledge_content_type_nonempty CHECK (btrim(content_type) <> ''),
    ADD COLUMN processor_id TEXT NOT NULL DEFAULT 'knowledge-text' CHECK (btrim(processor_id) <> ''),
    ADD COLUMN processor_version TEXT NOT NULL DEFAULT '1' CHECK (btrim(processor_version) <> ''),
    ADD COLUMN processor_options TEXT NOT NULL DEFAULT 'utf8=strict;bom=remove-leading;newline=lf;unicode=nfc;nul=reject'
        CHECK (btrim(processor_options) <> ''),
    ADD COLUMN page_ends INTEGER[] NOT NULL DEFAULT '{}';

-- New writers must always supply their recipe explicitly.
ALTER TABLE knowledge_document_revisions
    ALTER COLUMN processor_id DROP DEFAULT,
    ALTER COLUMN processor_version DROP DEFAULT,
    ALTER COLUMN processor_options DROP DEFAULT,
    ALTER COLUMN page_ends DROP DEFAULT;
