package com.alxarafe.erbas.knowledge.storage.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.DocumentReference;
import com.alxarafe.erbas.knowledge.storage.application.DocumentReadStore;
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

    public JdbcDocumentStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    public DocumentRevision create(String ownerModule, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        UUID id = UUID.randomUUID();
        var reference = new DocumentReference(id.toString(), ownerModule, permissions, modulePolicyRequired);
        validate(provenance, createdBy, content);
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO " + DOCUMENTS + " (id, owner_module) VALUES (?, ?)", id, ownerModule);
            return insert(reference, DocumentRevision.FIRST_NUMBER, provenance, createdBy, content);
        });
    }

    public DocumentRevision append(UUID documentId, Set<String> permissions, boolean modulePolicyRequired,
            String provenance, String createdBy, DocumentContent content) {
        Objects.requireNonNull(documentId);
        validate(provenance, createdBy, content);
        return transactions.execute(status -> {
            // Serialize numbering per document, while allowing unrelated documents to proceed.
            var owner = jdbc.queryForObject("SELECT owner_module FROM " + DOCUMENTS + " WHERE id = ? FOR UPDATE",
                    String.class, documentId);
            var reference = new DocumentReference(documentId.toString(), owner, permissions, modulePolicyRequired);
            int next = Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(revision_number), 0) FROM " + REVISIONS
                    + " WHERE document_id = ?", Integer.class, documentId), 1);
            return insert(reference, next, provenance, createdBy, content);
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
        return jdbc.query("SELECT r.*, d.owner_module FROM " + REVISIONS + " r JOIN " + DOCUMENTS
                + " d ON d.id = r.document_id WHERE r.document_id = ? AND r.revision_number = ?",
                (rs, row) -> revision(rs), documentId, number).stream().findFirst();
    }

    private DocumentRevision insert(DocumentReference reference, int number, String provenance,
            String createdBy, DocumentContent content) {
        UUID revisionId = UUID.randomUUID();
        UUID documentId = UUID.fromString(reference.id());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO " + REVISIONS
                    + " (id, document_id, revision_number, provenance, created_by, required_permissions, module_policy_required,"
                    + " content_type, original_content, normalized_text, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setObject(1, revisionId);
            statement.setObject(2, documentId);
            statement.setInt(3, number);
            statement.setString(4, provenance);
            statement.setString(5, createdBy);
            statement.setArray(6, connection.createArrayOf("text", reference.requiredPermissions().toArray(String[]::new)));
            statement.setBoolean(7, reference.modulePolicyRequired());
            statement.setString(8, content.contentType());
            statement.setBytes(9, content.original());
            statement.setString(10, content.text());
            statement.setString(11, content.sha256());
            return statement;
        });
        return revision(documentId, number).orElseThrow();
    }

    private static DocumentRevision revision(ResultSet rs) throws SQLException {
        UUID documentId = rs.getObject("document_id", UUID.class);
        var content = new DocumentContent(rs.getBytes("original_content"), rs.getString("content_type"));
        if (!content.sha256().equals(rs.getString("sha256")) || !content.text().equals(rs.getString("normalized_text")))
            throw new IllegalStateException("Document revision integrity failure");
        return new DocumentRevision(rs.getObject("id", UUID.class), documentId, rs.getInt("revision_number"),
                rs.getString("provenance"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                reference(rs), content);
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
