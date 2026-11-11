package com.alxarafe.erbas;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import com.alxarafe.erbas.knowledge.storage.domain.DocumentContent;
import com.alxarafe.erbas.knowledge.storage.infrastructure.JdbcDocumentStore;
import com.alxarafe.erbas.knowledge.storage.infrastructure.KnowledgeConfiguration;
import com.alxarafe.erbas.knowledge.storage.infrastructure.LocalDocumentStorage;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.application.AuthorizedDocumentReader;
import com.alxarafe.erbas.knowledge.DocumentVisibilityPolicy;
import com.alxarafe.erbas.knowledge.DocumentAccessPolicy;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import com.alxarafe.erbas.auth.application.UserAdministration;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.knowledge.DocumentReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;

class KnowledgePersistenceIT {
    private static final String SCHEMA = "knowledge_storage_it";
    private static final String OWNER = "erbas.wms";
    private static final String FOREIGN_OWNER = "erbas.assets";
    private static final Set<String> PERMISSIONS = Set.of("wms.read");
    private static final String SOURCE = "internal:test";
    private static final String CREATOR = "test-subject";
    private static final UUID EXISTING_ASSET = UUID.fromString("8a3e4fd1-1ef0-4e83-bfd8-d320716a763f");
    private static final String ASSET_TABLE = "assets_assets";
    private static final int REVISION_WAIT_SECONDS = 10;
    private static final int CONCURRENT_WRITERS = 2;
    private static final String[] LOCATIONS = {"classpath:db/core", "classpath:db/wms", "classpath:db/assets", "classpath:db/knowledge"};
    private static final String STORAGE_ROOT_ARG = "--" + KnowledgeConfiguration.STORAGE_ROOT_PROPERTY + "=";
    @TempDir Path root;
    @TempDir Path quarantine;

    @Test void hostUpgradePreservesDataAndProvidesTransactionalImmutableStorage() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("ERBAS_DATASOURCE_URL"));
        source.setUser(System.getenv("ERBAS_DATASOURCE_USERNAME"));
        source.setPassword(System.getenv("ERBAS_DATASOURCE_PASSWORD"));
        source.setCurrentSchema(SCHEMA);
        var jdbc = new JdbcTemplate(source);
        try {
            var previous = Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA)
                    .locations(LOCATIONS).target("8").load();
            previous.migrate();
            jdbc.update("INSERT INTO " + ASSET_TABLE + " (id, code, name, lifecycle, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    EXISTING_ASSET, "PRESERVED", "Existing asset", "DRAFT");
            var assets = jdbc.queryForList("SELECT * FROM " + ASSET_TABLE);
            var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            var marker = jdbc.queryForList("SELECT * FROM erbas_persistence_marker");
            // Budget this temporary pool for the concurrent writers, alongside the suite's cached contexts.
            try (var host = new SpringApplicationBuilder(ErbasApplication.class).run("--server.port=0",
                    STORAGE_ROOT_ARG + root,
                    "--spring.datasource.hikari.maximum-pool-size=" + CONCURRENT_WRITERS,
                    "--spring.datasource.url=" + System.getenv("ERBAS_DATASOURCE_URL") + "?currentSchema=" + SCHEMA,
                    "--spring.flyway.schemas=" + SCHEMA, "--spring.flyway.default-schema=" + SCHEMA)) {
                assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IS NULL OR version::int <= 8 ORDER BY installed_rank")).isEqualTo(history);
                assertThat(jdbc.queryForList("SELECT * FROM erbas_persistence_marker")).isEqualTo(marker);
                assertThat(jdbc.queryForList("SELECT * FROM " + ASSET_TABLE)).isEqualTo(assets);
                assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                        String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
                assertThat(host.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
                // Use the host-composed bean with its real DataSource and transaction manager.
                exercise(host.getBean(JdbcDocumentStore.class), jdbc, root);
                partialFailure(jdbc, root);
                confirmationFailure(host.getBean(JdbcDocumentStore.class), jdbc, root);
                assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
                        String.class, SCHEMA, JdbcDocumentStore.REVISIONS)).doesNotContain("original_content", "normalized_text");
                var store = host.getBean(JdbcDocumentStore.class);
                var denied = store.create(OWNER, PERMISSIONS, true, SOURCE, CREATOR,
                        new DocumentContent("protected".getBytes(StandardCharsets.UTF_8), DocumentContent.TXT));
                long subject = host.getBean(UserAdministration.class)
                        .create("default-reader@example.invalid", "fixture-password-long", true).user().userId();
                assertThat(host.getBean(AuthorizedDocumentReader.class).document(subject, OWNER, denied.documentId())).isEmpty();
            }
            try (var host = new SpringApplicationBuilder(ErbasApplication.class, ReadPolicies.class).run("--server.port=0",
                    STORAGE_ROOT_ARG + root,
                    "--spring.datasource.hikari.maximum-pool-size=" + CONCURRENT_WRITERS,
                    "--spring.datasource.url=" + System.getenv("ERBAS_DATASOURCE_URL") + "?currentSchema=" + SCHEMA,
                    "--spring.flyway.schemas=" + SCHEMA, "--spring.flyway.default-schema=" + SCHEMA)) {
                var users = host.getBean(UserAdministration.class);
                long subject = users.create("knowledge-reader@example.invalid", "fixture-password-long", false).user().userId();
                var store = host.getBean(JdbcDocumentStore.class);
                var first = store.create(OWNER, PERMISSIONS, true, SOURCE, CREATOR,
                        new DocumentContent("first".getBytes(StandardCharsets.UTF_8), DocumentContent.TXT));
                var second = store.append(first.documentId(), PERMISSIONS, true, SOURCE, CREATOR,
                        new DocumentContent("second".getBytes(StandardCharsets.UTF_8), DocumentContent.TXT));
                var reader = host.getBean(AuthorizedDocumentReader.class);
                assertThat(reader.document(subject, OWNER, first.documentId()).orElseThrow().id()).isEqualTo(second.id());
                var historical = reader.revision(subject, OWNER, first.documentId(), first.number()).orElseThrow();
                assertThat(historical.id()).isEqualTo(first.id());
                assertThat(historical.content().original()).isEqualTo(first.content().original());
                assertThat(historical.provenance()).isEqualTo(SOURCE);
                assertThat(reader.document(subject, FOREIGN_OWNER, first.documentId())).isEmpty();
                var scoped = store.create(OWNER, PERMISSIONS, true, SOURCE, CREATOR, first.content());
                host.getBean(ReadPolicies.class).restricted = scoped.documentId();
                assertThat(reader.document(subject, OWNER, scoped.documentId())).isEmpty();
                assertThat(reader.revision(subject, OWNER, scoped.documentId(), scoped.number())).isEmpty();
                assertThat(reader.document(subject, OWNER, first.documentId())).isPresent();
                var foreign = store.create(FOREIGN_OWNER, PERMISSIONS, true, SOURCE, CREATOR, first.content());
                assertThat(reader.document(subject, OWNER, foreign.documentId())).isEmpty();
                assertThat(reader.document(subject, FOREIGN_OWNER, foreign.documentId())).isEmpty();
                host.getBean(ReadPolicies.class).member = false;
                assertThat(reader.document(subject, OWNER, first.documentId())).isEmpty();
                host.getBean(ReadPolicies.class).member = true;
                users.update(Long.toString(subject), false, null);
                assertThat(reader.revision(subject, OWNER, first.documentId(), first.number())).isEmpty();
                assertThat(store.revision(first.documentId(), first.number()).orElseThrow().content().sha256())
                        .isEqualTo(first.content().sha256());
                UUID object = jdbc.queryForObject("SELECT original_object FROM " + JdbcDocumentStore.REVISIONS + " WHERE id = ?",
                        UUID.class, first.id());
                Path file = objectFile(root, object);
                byte[] saved = Files.readAllBytes(file);
                Files.write(file, new byte[]{0});
                // Disabled identity denies before physical storage, even when that object is damaged.
                assertThat(reader.revision(subject, OWNER, first.documentId(), first.number())).isEmpty();
                assertThatThrownBy(() -> store.revision(first.documentId(), first.number()))
                        .isInstanceOfSatisfying(StorageFailure.class, failure -> assertThat(failure.kind()).isEqualTo(StorageFailure.Kind.INTEGRITY));
                Files.write(file, saved);
            }
            reconcileOrphans(jdbc, root, quarantine);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReadPolicies {
        boolean member = true;
        UUID restricted;
        @Bean FunctionalPermissionPolicy knowledgeFixturePermission() {
            return (user, permission) -> PERMISSIONS.contains(permission);
        }
        @Bean DocumentVisibilityPolicy knowledgeFixtureVisibility() { return (user, document) -> true; }
        @Bean DocumentAccessPolicy knowledgeFixtureOwnerPolicy() {
            return new DocumentAccessPolicy() {
                public String module() { return OWNER; }
                public boolean allowed(UserIdentity user, DocumentReference document) {
                    return member && !document.id().equals(String.valueOf(restricted));
                }
            };
        }
    }

    private static void exercise(JdbcDocumentStore store, JdbcTemplate jdbc, Path root) throws Exception {
        byte[] bytes = "\uFEFF# Café\r\n".getBytes(StandardCharsets.UTF_8);
        var first = store.create(OWNER, PERMISSIONS, true, SOURCE, CREATOR, new DocumentContent(bytes, DocumentContent.MARKDOWN));
        assertThat(first.number()).isEqualTo(1);
        assertThat(first.content().original()).isEqualTo(bytes);
        assertThat(first.content().text()).isEqualTo("# Café\n");
        assertThat(first.content().sha256()).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(first.reference().requiredPermissions()).isEqualTo(PERMISSIONS);
        assertThat(first.reference().modulePolicyRequired()).isTrue();
        assertThat(first.reference().module()).isEqualTo(OWNER);
        assertThat(first.provenance()).isEqualTo(SOURCE);
        assertThat(first.createdBy()).isEqualTo(CREATOR);
        assertThat(first.createdAt()).isNotNull();
        assertThat(store.document(first.documentId()).orElseThrow().ownerModule()).isEqualTo(OWNER);
        var text = new DocumentContent("next".getBytes(StandardCharsets.UTF_8), DocumentContent.TXT);
        try (var executor = Executors.newFixedThreadPool(CONCURRENT_WRITERS)) {
            var a = executor.submit(() -> store.append(first.documentId(), PERMISSIONS, true, SOURCE, CREATOR, text));
            var b = executor.submit(() -> store.append(first.documentId(), PERMISSIONS, true, SOURCE, CREATOR, text));
            assertThat(Set.of(a.get(REVISION_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).number(),
                    b.get(REVISION_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).number())).containsExactlyInAnyOrder(2, 3);
        }
        assertThat(store.revision(first.documentId(), 1).orElseThrow().content().original()).isEqualTo(bytes);
        assertThat(store.revision(UUID.randomUUID(), 1)).isEmpty();
        assertThat(store.document(UUID.randomUUID())).isEmpty();
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.update("UPDATE " + JdbcDocumentStore.REVISIONS + " SET provenance = ? WHERE id = ?", "changed", first.id()));
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.update("DELETE FROM " + JdbcDocumentStore.REVISIONS + " WHERE id = ?", first.id()));
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> jdbc.update("INSERT INTO " + JdbcDocumentStore.REVISIONS
                + " (id, document_id, revision_number, provenance, created_by, required_permissions, module_policy_required, content_type, original_object, original_size, normalized_object, normalized_size, sha256, normalized_sha256)"
                + " SELECT ?, document_id, 99, provenance, created_by, required_permissions, module_policy_required, content_type, ?, original_size, ?, normalized_size, ?, normalized_sha256"
                + " FROM " + JdbcDocumentStore.REVISIONS + " WHERE id = ?", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "invalid-digest", first.id()));
        // Database-side failure occurs after document INSERT, proving the transaction removes it.
        jdbc.execute("ALTER TABLE " + JdbcDocumentStore.REVISIONS + " ADD CONSTRAINT test_reject_creator CHECK (created_by <> 'rejected')");
        int before = jdbc.queryForObject("SELECT count(*) FROM " + JdbcDocumentStore.DOCUMENTS, Integer.class);
        long objectsBefore = objectCount(root);
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> store.create(OWNER, PERMISSIONS, true, SOURCE, "rejected", text));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + JdbcDocumentStore.DOCUMENTS, Integer.class)).isEqualTo(before);
        // Complete objects survive DB rollback for safe, offline orphan reconciliation.
        assertThat(objectCount(root)).isEqualTo(objectsBefore + 2);
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> store.append(first.documentId(), PERMISSIONS, true, SOURCE, "rejected", text));
        var revisedPermissions = Set.of("wms.other");
        var fourth = store.append(first.documentId(), revisedPermissions, false, SOURCE, CREATOR, text);
        assertThat(fourth.number()).isEqualTo(4);
        assertThat(fourth.reference().requiredPermissions()).isEqualTo(revisedPermissions);
        assertThat(store.revision(first.documentId(), 1).orElseThrow().reference()).isEqualTo(first.reference());
        assertThatIllegalArgumentException().isThrownBy(() -> store.create(OWNER, Set.of(), false, SOURCE, CREATOR, text));
        var explicit = store.create(new StorageContext(OWNER, UUID.randomUUID()), PERMISSIONS, true, SOURCE, CREATOR, text);
        assertThat(store.revision(explicit.documentId(), explicit.number()).orElseThrow().content().original()).isEqualTo(text.original());
        var normalizedId = jdbc.queryForObject("SELECT normalized_object FROM " + JdbcDocumentStore.REVISIONS + " WHERE id = ?",
                UUID.class, explicit.id());
        var normalizedFile = objectFile(root, normalizedId);
        var saved = Files.readAllBytes(normalizedFile);
        Files.write(normalizedFile, new byte[saved.length]);
        assertThatThrownBy(() -> store.revision(explicit.documentId(), explicit.number()))
                .isInstanceOfSatisfying(StorageFailure.class, failure -> assertThat(failure.kind()).isEqualTo(StorageFailure.Kind.INTEGRITY));
        Files.write(normalizedFile, saved);
    }

    private static long objectCount(Path root) throws Exception {
        try (var files = Files.list(root)) { return files.filter(path -> path.toString().endsWith(LocalDocumentStorage.OBJECT_SUFFIX)).count(); }
    }

    private static Path objectFile(Path root, UUID object) throws Exception {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.toString().endsWith(object + LocalDocumentStorage.OBJECT_SUFFIX)).findFirst().orElseThrow();
        }
    }

    private static void partialFailure(JdbcTemplate jdbc, Path root) throws Exception {
        var local = new LocalDocumentStorage(root);
        DocumentStorage failure = new DocumentStorage() {
            int writes;
            public StoredObject write(StorageContext context, java.io.InputStream source) {
                if (++writes == 2) throw new StorageFailure(StorageFailure.Kind.WRITE);
                return local.write(context, source);
            }
            public void read(StorageContext context, StoredObject object, java.io.OutputStream target) { local.read(context, object, target); }
        };
        var store = new JdbcDocumentStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), failure);
        var before = jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.DOCUMENTS);
        var revisions = jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.REVISIONS);
        long objects = objectCount(root);
        assertThatThrownBy(() -> store.create(OWNER, PERMISSIONS, true, SOURCE, CREATOR,
                new DocumentContent(new byte[]{1}, DocumentContent.TXT))).isInstanceOf(StorageFailure.class);
        assertThat(jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.DOCUMENTS)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.REVISIONS)).isEqualTo(revisions);
        assertThat(objectCount(root)).isEqualTo(objects + 1);
    }

    private static void confirmationFailure(JdbcDocumentStore store, JdbcTemplate jdbc, Path root) throws Exception {
        jdbc.execute("""
                CREATE FUNCTION knowledge_test_reject_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.created_by = 'commit-rejected' THEN RAISE EXCEPTION 'fixture confirmation rejected'; END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("CREATE CONSTRAINT TRIGGER knowledge_test_confirmation AFTER INSERT ON " + JdbcDocumentStore.REVISIONS
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION knowledge_test_reject_confirmation()");
        var before = jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.DOCUMENTS);
        long objects = objectCount(root);
        assertThatThrownBy(() -> store.create(OWNER, PERMISSIONS, true, SOURCE, "commit-rejected",
                new DocumentContent(new byte[]{1}, DocumentContent.TXT))).hasStackTraceContaining("fixture confirmation rejected");
        assertThat(jdbc.queryForList("SELECT id FROM " + JdbcDocumentStore.DOCUMENTS)).isEqualTo(before);
        assertThat(objectCount(root)).isEqualTo(objects + 2);
    }

    private static void reconcileOrphans(JdbcTemplate jdbc, Path root, Path quarantine) throws Exception {
        // Same inventory query as the operator procedure, covering all historical revisions.
        var expected = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT encode(sha256(convert_to(d.owner_module, 'UTF8') || decode('00', 'hex')
                       || convert_to(d.storage_scope::text, 'UTF8')), 'hex')
                       || '-' || object_id::text || '.blob'
                FROM knowledge_documents d
                JOIN knowledge_document_revisions r ON r.document_id = d.id
                CROSS JOIN LATERAL (VALUES (r.original_object), (r.normalized_object)) objects(object_id)
                ORDER BY 1
                """, String.class));
        java.util.List<Path> actual;
        try (var files = Files.list(root)) { actual = files.toList(); }
        assertThat(actual.stream().map(path -> path.getFileName().toString()).toList()).containsAll(expected);
        var orphans = actual.stream().filter(path -> !expected.contains(path.getFileName().toString())).toList();
        assertThat(orphans).isNotEmpty();
        for (var orphan : orphans) {
            assertThat(Files.isSymbolicLink(orphan)).isFalse();
            assertThat(Files.isRegularFile(orphan)).isTrue();
            assertThat(orphan.getParent()).isEqualTo(root);
            Files.move(orphan, quarantine.resolve(orphan.getFileName()));
        }
        assertThat(objectCount(root)).isEqualTo(expected.size());
        var store = new JdbcDocumentStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), new LocalDocumentStorage(root));
        jdbc.query("SELECT document_id, revision_number FROM " + JdbcDocumentStore.REVISIONS, rs -> {
            assertThat(store.revision(rs.getObject("document_id", UUID.class), rs.getInt("revision_number"))).isPresent();
        });
    }
}
