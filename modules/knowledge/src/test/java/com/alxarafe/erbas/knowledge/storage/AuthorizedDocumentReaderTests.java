package com.alxarafe.erbas.knowledge.storage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.knowledge.*;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.*;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AuthorizedDocumentReaderTests {
    private static final String OWNER = KnowledgeModule.ID;
    private static final String FOREIGN = "fixture.other";
    private static final String READ = "fixture.read";
    private static final String RESTRICTED = "fixture.restricted";
    private static final long SUBJECT = 7;
    private final UUID id = UUID.randomUUID();
    private UserIdentity user = new UserIdentity(SUBJECT, "reader@example.invalid", true, false);
    private boolean enabled = true, granted = true, visible = true, member = true;
    private boolean failedAuthority;
    private int contentReads;
    private Runnable onLoad = () -> { };
    private final Map<Integer, DocumentRevision> revisions = new HashMap<>();
    private final DocumentReadStore store = new DocumentReadStore() {
        public Optional<Integer> latestRevision(UUID document) {
            return id.equals(document) ? revisions.keySet().stream().max(Integer::compareTo) : Optional.empty();
        }
        public Optional<DocumentReference> reference(UUID document, int number) {
            return id.equals(document) ? Optional.ofNullable(revisions.get(number)).map(DocumentRevision::reference) : Optional.empty();
        }
        public Optional<DocumentRevision> revision(UUID document, int number) {
            contentReads++;
            onLoad.run();
            return Optional.ofNullable(revisions.get(number));
        }
    };

    AuthorizedDocumentReaderTests() { revisions.put(1, value(1, READ, true)); }

    private DocumentRevision value(int number, String permission, boolean required) {
        return new DocumentRevision(UUID.randomUUID(), id, number, "internal:fixture", "fixture", Instant.now(),
                new DocumentReference(id.toString(), OWNER, Set.of(permission), required),
                new DocumentContent(("secret-" + number).getBytes(StandardCharsets.UTF_8), DocumentContent.TXT));
    }

    private AuthorizedDocumentReader reader(String policyOwner, boolean register) {
        var modules = new ModuleRegistry(List.of(() -> List.of(new KnowledgeModule(), new ErbasModule() {
            public ModuleDescriptor descriptor() { return new ModuleDescriptor(FOREIGN, ModuleApi.CURRENT_VERSION, List.of()); }
            public void initialize() { }
        })));
        var states = new ModuleStateService(modules, new ModuleStateStore() {
            public Optional<Boolean> find(String module) { return Optional.of(enabled); }
            public void update(java.util.function.Consumer<Map<String, Boolean>> change) { throw new UnsupportedOperationException(); }
        });
        DocumentAccessPolicy policy = new DocumentAccessPolicy() {
            public String module() { return policyOwner; }
            public boolean allowed(UserIdentity current, DocumentReference document) { return member; }
        };
        return new AuthorizedDocumentReader(store, new DocumentAuthorization(modules, states,
                subject -> Optional.ofNullable(user), List.of((current, permission) -> granted && READ.equals(permission)),
                (current, document) -> { if (failedAuthority) throw new IllegalStateException(); return visible; },
                register ? List.of(policy) : List.of()));
    }

    @Test void returnsLatestAndHistoricalContentWithProtectedMetadata() {
        revisions.put(2, value(2, READ, true));
        var reader = reader(OWNER, true);
        assertThat(reader.document(SUBJECT, OWNER, id)).contains(revisions.get(2));
        assertThat(reader.revision(SUBJECT, OWNER, id, 1)).contains(revisions.get(1));
    }

    @ParameterizedTest @ValueSource(strings = {"identity", "disabledIdentity", "replacedIdentity", "module", "permission", "visibility", "membership", "failure"})
    void everyAuthorityCanRevokeSubsequentCurrentAndHistoricalReads(String authority) {
        revisions.put(2, value(2, READ, true));
        var reader = reader(OWNER, true);
        assertThat(reader.document(SUBJECT, OWNER, id)).isPresent();
        switch (authority) {
            case "identity" -> user = null;
            case "disabledIdentity" -> user = new UserIdentity(SUBJECT, "reader@example.invalid", false, true);
            case "replacedIdentity" -> user = new UserIdentity(SUBJECT + 1, "other@example.invalid", true, true);
            case "module" -> enabled = false;
            case "permission" -> granted = false;
            case "visibility" -> visible = false;
            case "membership" -> member = false;
            case "failure" -> failedAuthority = true;
            default -> throw new AssertionError(authority);
        }
        int before = contentReads;
        assertThat(reader.document(SUBJECT, OWNER, id)).isEmpty();
        assertThat(reader.revision(SUBJECT, OWNER, id, 1)).isEmpty();
        assertThat(contentReads).isEqualTo(before);
    }

    @Test void missingAndForeignPoliciesCannotSatisfyMandatoryOwnerPolicy() {
        assertThat(reader(OWNER, false).document(SUBJECT, OWNER, id)).isEmpty();
        assertThat(reader(FOREIGN, true).revision(SUBJECT, OWNER, id, 1)).isEmpty();
        assertThat(contentReads).isZero();
    }

    @Test void identifierManipulationAndInvalidInputDiscloseNothing() {
        var reader = reader(OWNER, true);
        assertThat(reader.document(SUBJECT, FOREIGN, id)).isEmpty();
        assertThat(reader.document(SUBJECT, OWNER, UUID.randomUUID())).isEmpty();
        assertThat(reader.document(SUBJECT, OWNER, null)).isEmpty();
        assertThat(reader.document(SUBJECT, null, id)).isEmpty();
        assertThat(reader.revision(SUBJECT, OWNER, id, 0)).isEmpty();
        assertThat(reader.revision(SUBJECT, OWNER, id, 99)).isEmpty();
        assertThat(contentReads).isZero();
    }

    @Test void historicalPermissionAndCurrentPermissionAreBothMandatory() {
        revisions.put(2, value(2, RESTRICTED, true));
        assertThat(reader(OWNER, true).revision(SUBJECT, OWNER, id, 1)).isEmpty();
        revisions.put(1, value(1, RESTRICTED, true));
        revisions.put(2, value(2, READ, true));
        assertThat(reader(OWNER, true).revision(SUBJECT, OWNER, id, 1)).isEmpty();
        assertThat(contentReads).isZero();
    }

    @Test void revocationDuringContentLoadDiscardsAllProtectedValues() {
        onLoad = () -> granted = false;
        assertThat(reader(OWNER, true).document(SUBJECT, OWNER, id)).isEmpty();
        assertThat(contentReads).isEqualTo(1);
    }

    @Test void restrictiveAppendDuringContentLoadAlsoDenies() {
        onLoad = () -> revisions.put(2, value(2, RESTRICTED, true));
        assertThat(reader(OWNER, true).revision(SUBJECT, OWNER, id, 1)).isEmpty();
    }
}
