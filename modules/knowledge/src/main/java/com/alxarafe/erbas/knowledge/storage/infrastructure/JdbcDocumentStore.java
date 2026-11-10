package com.alxarafe.erbas.knowledge.storage.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.DocumentReference;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentRevision;
import com.alxarafe.erbas.knowledge.storage.domain.StoredDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Trusted infrastructure only. Application consumers must use the authorized reader before disclosure. */
public final class JdbcDocumentStore implements DocumentReadStore {
    public static final String DOCUMENTS = "knowledge_documents";
    public static final String REVISIONS = "knowledge_document_revisions";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DocumentStorage storage;

    public JdbcDocumentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, DocumentStorage storage) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.storage = Objects.requireNonNull(storage);
    }

    public DocumentRevision create(String ownerModule, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        UUID id = UUID.randomUUID();
        return create(id, new StorageContext(ownerModule, id), permissions, modulePolicyRequired, provenance, createdBy, content);
    }

    /** Explicit trusted scope for future tenant-owned composition; it never grants read permission. */
    public DocumentRevision create(StorageContext context, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        return create(UUID.randomUUID(), context, permissions, modulePolicyRequired, provenance, createdBy, content);
    }

    private DocumentRevision create(UUID id, StorageContext context, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        var reference = new DocumentReference(id.toString(), context.module(), permissions, modulePolicyRequired);
        validate(provenance, createdBy, content);
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO " + DOCUMENTS + " (id, owner_module, storage_scope) VALUES (?, ?, ?)",
                    id, context.module(), context.scope());
            return insert(context, reference, DocumentRevision.FIRST_NUMBER, provenance, createdBy, content);
        });
    }

    public DocumentRevision append(UUID documentId, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        Objects.requireNonNull(documentId);
        validate(provenance, createdBy, content);
        return transactions.execute(status -> {
            // Serialize numbering per document, while allowing unrelated documents to proceed.
            var context = jdbc.queryForObject("SELECT owner_module, storage_scope FROM " + DOCUMENTS + " WHERE id = ? FOR UPDATE",
                    (rs, row) -> new StorageContext(rs.getString("owner_module"), rs.getObject("storage_scope", UUID.class)), documentId);
            var reference = new DocumentReference(documentId.toString(), context.module(), permissions, modulePolicyRequired);
            int next = Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(revision_number), 0) FROM " + REVISIONS
                    + " WHERE document_id = ?", Integer.class, documentId), 1);
            return insert(context, reference, next, provenance, createdBy, content);
        });
    }

    public Optional<StoredDocument> document(UUID id) {
        return jdbc.query("SELECT * FROM " + DOCUMENTS + " WHERE id = ?", (rs, row) -> new StoredDocument(
                rs.getObject("id", UUID.class), rs.getString("owner_module"), rs.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst();
    }

    @Override public Optional<Integer> latestRevision(UUID documentId) {
        return jdbc.query("SELECT MAX(revision_number) FROM " + REVISIONS + " WHERE document_id = ?",
                (rs, row) -> (Integer) rs.getObject(1), documentId).stream().filter(Objects::nonNull).findFirst();
    }

    @Override public Optional<DocumentReference> reference(UUID documentId, int number) {
        return jdbc.query("SELECT r.document_id, d.owner_module, r.required_permissions, r.module_policy_required FROM "
                + REVISIONS + " r JOIN " + DOCUMENTS + " d ON d.id = r.document_id"
                + " WHERE r.document_id = ? AND r.revision_number = ?", (rs, row) -> reference(rs), documentId, number)
                .stream().findFirst();
    }

    public Optional<DocumentRevision> revision(UUID documentId, int number) {
        return jdbc.query("SELECT r.*, d.owner_module, d.storage_scope FROM " + REVISIONS + " r JOIN " + DOCUMENTS
                + " d ON d.id = r.document_id WHERE r.document_id = ? AND r.revision_number = ?",
                (rs, row) -> revision(rs), documentId, number).stream().findFirst();
    }

    private DocumentRevision insert(StorageContext context, DocumentReference reference, int number, String provenance,
            String createdBy, DocumentContent content) {
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.fromString(reference.id());
        // Publish complete immutable objects before confirming references in PostgreSQL.
        // On DB rollback these are intentional orphans, handled by the offline reconciliation procedure.
        var original = storage.write(context, new ByteArrayInputStream(content.original()));
        var normalized = storage.write(context, new ByteArrayInputStream(content.text().getBytes(StandardCharsets.UTF_8)));
        if (!content.sha256().equals(original.sha256())) throw new StorageFailure(StorageFailure.Kind.INTEGRITY);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO " + REVISIONS
                    + " (id, document_id, revision_number, provenance, created_by, required_permissions, module_policy_required,"
                    + " content_type, original_object, original_size, normalized_object, normalized_size, sha256, normalized_sha256)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setObject(1, revisionId);
            statement.setObject(2, documentId);
            statement.setInt(3, number);
            statement.setString(4, provenance);
            statement.setString(5, createdBy);
            statement.setArray(6, connection.createArrayOf("text", reference.requiredPermissions().toArray(String[]::new)));
            statement.setBoolean(7, reference.modulePolicyRequired());
            statement.setString(8, content.contentType());
            statement.setObject(9, original.id());
            statement.setLong(10, original.size());
            statement.setObject(11, normalized.id());
            statement.setLong(12, normalized.size());
            statement.setString(13, original.sha256());
            statement.setString(14, normalized.sha256());
            return statement;
        });
        return revision(documentId, number).orElseThrow();
    }

    private DocumentRevision revision(ResultSet rs) throws SQLException {
        UUID documentId = rs.getObject("document_id", UUID.class);
        var context = new StorageContext(rs.getString("owner_module"), rs.getObject("storage_scope", UUID.class));
        byte[] original = read(context, new StoredObject(rs.getObject("original_object", UUID.class),
                rs.getString("sha256"), rs.getLong("original_size")));
        byte[] normalized = read(context, new StoredObject(rs.getObject("normalized_object", UUID.class),
                rs.getString("normalized_sha256"), rs.getLong("normalized_size")));
        var content = new DocumentContent(original, rs.getString("content_type"));
        if (!Arrays.equals(content.text().getBytes(StandardCharsets.UTF_8), normalized))
            throw new StorageFailure(StorageFailure.Kind.INTEGRITY);
        return new DocumentRevision(rs.getObject("id", UUID.class), documentId, rs.getInt("revision_number"),
                rs.getString("provenance"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                reference(rs), content);
    }

    private byte[] read(StorageContext context, StoredObject object) {
        var staged = new ByteArrayOutputStream();
        storage.read(context, object, staged);
        return staged.toByteArray();
    }

    private static DocumentReference reference(ResultSet rs) throws SQLException {
        var permissions = rs.getArray("required_permissions");
        try {
            return new DocumentReference(rs.getObject("document_id", UUID.class).toString(), rs.getString("owner_module"),
                    Set.copyOf(Arrays.asList((String[]) permissions.getArray())), rs.getBoolean("module_policy_required"));
        } finally { permissions.free(); }
    }

    private static void validate(String provenance, String createdBy, DocumentContent content) {
        if (provenance == null || provenance.isBlank() || createdBy == null || createdBy.isBlank())
            throw new IllegalArgumentException("Document provenance and creator are required");
        Objects.requireNonNull(content);
    }
}
