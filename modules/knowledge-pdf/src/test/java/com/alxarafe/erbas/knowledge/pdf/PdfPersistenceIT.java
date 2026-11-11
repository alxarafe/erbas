package com.alxarafe.erbas.knowledge.pdf;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.knowledge.DocumentAccessPolicy;
import com.alxarafe.erbas.knowledge.DocumentAuthorization;
import com.alxarafe.erbas.knowledge.DocumentReference;
import com.alxarafe.erbas.knowledge.storage.KnowledgeModule;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.*;
import com.alxarafe.erbas.knowledge.storage.infrastructure.*;
import com.alxarafe.erbas.modularity.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;

class PdfPersistenceIT {
    private static final String SCHEMA = "knowledge_processing_it";
    private static final String LEGACY_VERSION = "9";
    private static final String OWNER = KnowledgeModule.ID;
    private static final String READ = "fixture.document.read";
    private static final String SOURCE = "internal:processing-fixture";
    private static final String CREATOR = "fixture";
    private static final long SUBJECT = 7;
    @TempDir Path root;

    @Test void upgradesV9AndPersistsCompleteImmutablePdfRevisionsThroughTheStorageSpi() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("ERBAS_DATASOURCE_URL"));
        source.setUser(System.getenv("ERBAS_DATASOURCE_USERNAME"));
        source.setPassword(System.getenv("ERBAS_DATASOURCE_PASSWORD"));
        source.setCurrentSchema(SCHEMA);
        var jdbc = new JdbcTemplate(source);
        var storage = new LocalDocumentStorage(root);
        try {
            Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA)
                    .locations("classpath:db/knowledge").target(LEGACY_VERSION).load().migrate();
            UUID legacy = legacyRevision(jdbc, storage);
            var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            var upgraded = Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA)
                    .locations("classpath:db/knowledge").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IS NULL OR version = ? ORDER BY installed_rank",
                    LEGACY_VERSION)).isEqualTo(history);
            var transactions = new DataSourceTransactionManager(source);
            var store = new JdbcDocumentStore(jdbc, transactions, storage);
            var historical = store.revision(legacy, DocumentRevision.FIRST_NUMBER).orElseThrow().content();
            assertThat(historical.processing()).isEqualTo(ProcessingDetails.TEXT);
            assertThat(historical.original()).isEqualTo("legacy\r\n".getBytes(StandardCharsets.UTF_8));
            assertThat(historical.text()).isEqualTo("legacy\n");
            var processing = new DocumentProcessingService(ProcessingLimits.DEFAULT, List.of(new PdfDocumentProcessor()));
            byte[] original = PdfFixtures.document("Page one", "Page two");
            var processed = processing.process(new ByteArrayInputStream(original), PdfDocumentProcessor.PDF);
            var revision = store.create(OWNER, Set.of(READ), true, SOURCE, CREATOR, processed);
            var reread = new JdbcDocumentStore(jdbc, transactions, storage).revision(revision.documentId(), revision.number()).orElseThrow();
            assertThat(reread.content().original()).isEqualTo(original);
            assertThat(reread.content().sha256()).isEqualTo(ContentDigest.sha256(original));
            assertThat(reread.content().text()).isEqualTo(processed.text());
            assertThat(reread.content().processing()).isEqualTo(processed.processing());
            assertThat(jdbc.queryForObject("SELECT normalized_sha256 FROM " + JdbcDocumentStore.REVISIONS + " WHERE id = ?",
                    String.class, revision.id())).isEqualTo(ContentDigest.sha256(processed.text().getBytes(StandardCharsets.UTF_8)));
            store.append(revision.documentId(), Set.of(READ), true, SOURCE, CREATOR,
                    processing.process(new ByteArrayInputStream(PdfFixtures.document("Next")), PdfDocumentProcessor.PDF));
            assertThat(store.revision(revision.documentId(), revision.number()).orElseThrow().content().processing()).isEqualTo(processed.processing());
            var member = new AtomicBoolean(true);
            var reader = reader(store, member);
            assertThat(reader.revision(SUBJECT, OWNER, revision.documentId(), revision.number()).orElseThrow().content().original()).isEqualTo(original);
            member.set(false);
            assertThat(reader.document(SUBJECT, OWNER, revision.documentId())).isEmpty();
            assertThat(reader.revision(SUBJECT, OWNER, revision.documentId(), revision.number())).isEmpty();
            assertThatThrownBy(() -> jdbc.update("UPDATE " + JdbcDocumentStore.REVISIONS + " SET " + JdbcDocumentStore.PROCESSOR_VERSION + " = ? WHERE id = ?", "changed", revision.id()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            long documents = count(jdbc, JdbcDocumentStore.DOCUMENTS), revisions = count(jdbc, JdbcDocumentStore.REVISIONS);
            assertThatThrownBy(() -> store.create(OWNER, Set.of(READ), true, SOURCE, CREATOR,
                    processing.process(new ByteArrayInputStream(PdfFixtures.document("")), PdfDocumentProcessor.PDF)))
                    .isInstanceOf(ProcessingFailure.class);
            var partial = new DocumentStorage() {
                int writes;
                public StoredObject write(StorageContext context, java.io.InputStream input) {
                    if (++writes == 2) throw new StorageFailure(StorageFailure.Kind.WRITE);
                    return storage.write(context, input);
                }
                public void read(StorageContext context, StoredObject object, OutputStream output) { storage.read(context, object, output); }
            };
            assertThatThrownBy(() -> new JdbcDocumentStore(jdbc, transactions, partial)
                    .create(OWNER, Set.of(READ), true, SOURCE, CREATOR, processed)).isInstanceOf(StorageFailure.class);
            assertThat(count(jdbc, JdbcDocumentStore.DOCUMENTS)).isEqualTo(documents);
            assertThat(count(jdbc, JdbcDocumentStore.REVISIONS)).isEqualTo(revisions);
            var partialRead = new DocumentStorage() {
                public StoredObject write(StorageContext context, java.io.InputStream input) { return storage.write(context, input); }
                public void read(StorageContext context, StoredObject object, OutputStream output) {
                    try { output.write('x'); } catch (java.io.IOException exception) { throw new AssertionError(exception); }
                    throw new StorageFailure(StorageFailure.Kind.READ);
                }
            };
            assertThatThrownBy(() -> new JdbcDocumentStore(jdbc, transactions, partialRead).revision(revision.documentId(), revision.number()))
                    .isInstanceOf(StorageFailure.class);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    private static long count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private static UUID legacyRevision(JdbcTemplate jdbc, DocumentStorage storage) {
        UUID id = UUID.randomUUID();
        var context = new StorageContext(OWNER, id);
        var content = new DocumentContent("legacy\r\n".getBytes(StandardCharsets.UTF_8), DocumentContent.TXT);
        var original = storage.write(context, new ByteArrayInputStream(content.original()));
        var normalized = storage.write(context, new ByteArrayInputStream(content.text().getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO knowledge_documents (id, owner_module, storage_scope) VALUES (?, ?, ?)", id, OWNER, id);
        jdbc.update("INSERT INTO knowledge_document_revisions (id, document_id, revision_number, provenance, created_by,"
                + " required_permissions, module_policy_required, content_type, original_object, original_size, normalized_object,"
                + " normalized_size, sha256, normalized_sha256) VALUES (?, ?, ?, ?, ?, ARRAY[?], true, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, DocumentRevision.FIRST_NUMBER, SOURCE, CREATOR, READ, content.contentType(),
                original.id(), original.size(), normalized.id(), normalized.size(), original.sha256(), normalized.sha256());
        return id;
    }

    private static AuthorizedDocumentReader reader(JdbcDocumentStore store, AtomicBoolean member) {
        var modules = new ModuleRegistry(List.of(() -> List.of(new KnowledgeModule())));
        var states = new ModuleStateService(modules, new ModuleStateStore() {
            public Optional<Boolean> find(String module) { return Optional.of(true); }
            public void update(java.util.function.Consumer<java.util.Map<String, Boolean>> change) { throw new UnsupportedOperationException(); }
        });
        DocumentAccessPolicy policy = new DocumentAccessPolicy() {
            public String module() { return OWNER; }
            public boolean allowed(UserIdentity user, DocumentReference document) { return member.get(); }
        };
        return new AuthorizedDocumentReader(store, new DocumentAuthorization(modules, states,
                subject -> Optional.of(new UserIdentity(SUBJECT, "reader@example.invalid", true, false)),
                List.of((user, permission) -> READ.equals(permission)), (user, document) -> true, List.of(policy)));
    }
}
