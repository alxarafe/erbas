package com.alxarafe.erbas.wms;

import java.util.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.knowledge.*;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Organization and document fixtures only: no department model or grants enter CORE. */
class WmsDocumentAuthorizationTests {
    private static final String OTHER_MODULE = "fixture.other";
    private static final String REQUIRED_DEPARTMENT = "cold-storage";
    private static final String OTHER_DEPARTMENT = "shipping";
    private static final String DOCUMENT_ID = "cold-storage-manual";
    private static final UserIdentity USER = new UserIdentity(7, "reader@example.invalid", true, true);
    private static final DocumentReference DOCUMENT = new DocumentReference(DOCUMENT_ID, WmsModule.ID,
            Set.of(WmsCapabilities.UOMS_READ), true);
    private static final DocumentReference OTHER = new DocumentReference("other-manual", OTHER_MODULE,
            Set.of(WmsCapabilities.UOMS_READ), true);
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new WmsModule(), new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(OTHER_MODULE, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final Map<String, Boolean> moduleStates = new HashMap<>();
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String module) { return Optional.ofNullable(moduleStates.get(module)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(moduleStates); }
    });
    private UserIdentity currentUser = USER;
    private boolean functionalGrant = true;
    private boolean visible = true;
    private final Map<Long, Set<String>> memberships = new HashMap<>(Map.of(USER.userId(), Set.of(REQUIRED_DEPARTMENT)));
    private final Set<String> hiddenDocuments = new HashSet<>();
    private final List<String> evaluatedOwners = new ArrayList<>();
    private final FunctionalPermissionPolicy functional = (user, permission) -> functionalGrant
            && new WmsCapabilities().granted(user, permission);
    private final DocumentVisibilityPolicy visibility = (user, document) -> visible && !hiddenDocuments.contains(document.id());
    private final DocumentAccessPolicy wms = policy(WmsModule.ID, true);

    private DocumentAccessPolicy policy(String owner, boolean permit) {
        return new DocumentAccessPolicy() {
            public String module() { return owner; }
            public boolean allowed(UserIdentity user, DocumentReference document) {
                evaluatedOwners.add(owner);
                return permit && memberships.getOrDefault(user.userId(), Set.of()).contains(REQUIRED_DEPARTMENT);
            }
        };
    }
    private DocumentAuthorization authorization(DocumentAccessPolicy... policies) {
        states.synchronize();
        return new DocumentAuthorization(modules, states, id -> Optional.ofNullable(currentUser),
                List.of(functional), visibility, List.of(policies));
    }
    private boolean allowed(DocumentAuthorization authorization) { return authorization.allowed(USER.userId(), DOCUMENT); }

    @Test void wmsRegistersOwnRestrictionThroughExplicitComposition() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DocumentAccessPolicy.class, () -> wms);
            context.refresh();
            var service = authorization(context.getBeansOfType(DocumentAccessPolicy.class).values().toArray(DocumentAccessPolicy[]::new));
            assertThat(allowed(service)).isTrue();
        }
    }
    @Test void noFunctionalPermissionCannotBeOverriddenByVisibilityOrModule() {
        var service = authorization(wms);
        functionalGrant = false;
        assertThat(allowed(service)).isFalse();
        assertThat(evaluatedOwners).isEmpty();
    }
    @Test void realWmsAdministratorBridgeRemainsUnchanged() {
        var service = authorization(wms);
        currentUser = new UserIdentity(USER.userId(), USER.email(), true, false);
        assertThat(allowed(service)).isFalse();
    }
    @Test void everyRequiredFunctionalPermissionMustBeGranted() {
        var document = new DocumentReference(DOCUMENT_ID, WmsModule.ID,
                Set.of(WmsCapabilities.UOMS_READ, "fixture.not-granted"), false);
        assertThat(authorization(wms).allowed(USER.userId(), document)).isFalse();
    }
    @Test void visibilityDenialCannotBeOverriddenByModule() {
        var service = authorization(wms);
        visible = false;
        assertThat(allowed(service)).isFalse();
        assertThat(evaluatedOwners).isEmpty();
    }
    @Test void missingMembershipDeniesEvenAdministrator() {
        memberships.clear();
        assertThat(allowed(authorization(wms))).isFalse();
    }
    @Test void multipleMembershipsAreOwnedEntirelyByWmsFixture() {
        memberships.put(USER.userId(), Set.of(OTHER_DEPARTMENT, REQUIRED_DEPARTMENT));
        assertThat(allowed(authorization(wms))).isTrue();
    }
    @Test void membershipRevocationAndRestorationApplyOnNextQuery() {
        var service = authorization(wms);
        assertThat(allowed(service)).isTrue();
        memberships.put(USER.userId(), Set.of(OTHER_DEPARTMENT));
        assertThat(allowed(service)).isFalse();
        memberships.put(USER.userId(), Set.of(REQUIRED_DEPARTMENT));
        assertThat(allowed(service)).isTrue();
    }
    @Test void functionalAndVisibilityRevocationApplyOnNextQuery() {
        var service = authorization(wms);
        assertThat(allowed(service)).isTrue();
        functionalGrant = false;
        assertThat(allowed(service)).isFalse();
        functionalGrant = true;
        visible = false;
        assertThat(allowed(service)).isFalse();
        visible = true;
        assertThat(allowed(service)).isTrue();
    }
    @Test void disabledDeletedOrMismatchedIdentityIsDeniedOnNextQuery() {
        var service = authorization(wms);
        assertThat(allowed(service)).isTrue();
        currentUser = new UserIdentity(USER.userId(), USER.email(), false, true);
        assertThat(allowed(service)).isFalse();
        currentUser = null;
        assertThat(allowed(service)).isFalse();
        currentUser = new UserIdentity(8, "other@example.invalid", true, true);
        assertThat(allowed(service)).isFalse();
    }
    @Test void ownerModuleDisableAndRestoreApplyOnNextQuery() {
        var service = authorization(wms);
        assertThat(allowed(service)).isTrue();
        states.disable(WmsModule.ID);
        assertThat(allowed(service)).isFalse();
        states.enable(WmsModule.ID);
        assertThat(allowed(service)).isTrue();
    }
    @Test void missingRequiredOwnerPolicyDeniesEvenWhenOtherModuleAllows() {
        assertThat(allowed(authorization(policy(OTHER_MODULE, true)))).isFalse();
        assertThat(evaluatedOwners).isEmpty();
    }
    @Test void wmsCannotAuthorizeAnotherModulesDocument() {
        assertThat(authorization(wms).allowed(USER.userId(), OTHER)).isFalse();
        assertThat(evaluatedOwners).isEmpty();
    }
    @Test void foreignDenialDoesNotParticipateInWmsDecision() {
        assertThat(allowed(authorization(wms, policy(OTHER_MODULE, false)))).isTrue();
        assertThat(evaluatedOwners).containsExactly(WmsModule.ID);
    }
    @Test void allOwnerPoliciesAreCumulativeRegardlessOfOrder() {
        assertThat(allowed(authorization(wms, policy(WmsModule.ID, false)))).isFalse();
        assertThat(allowed(authorization(policy(WmsModule.ID, false), wms))).isFalse();
    }
    @Test void optionalPolicyOnlySkipsAbsenceAndNeverSkipsRegisteredVeto() {
        var document = new DocumentReference(DOCUMENT_ID, WmsModule.ID, DOCUMENT.requiredPermissions(), false);
        assertThat(authorization().allowed(USER.userId(), document)).isTrue();
        assertThat(authorization(policy(WmsModule.ID, false)).allowed(USER.userId(), document)).isFalse();
    }
    @Test void unknownOwnerAndUnavailableModuleStateDeny() {
        var service = authorization(wms);
        var document = new DocumentReference(DOCUMENT_ID, "unavailable.module", DOCUMENT.requiredPermissions(), false);
        assertThat(service.allowed(USER.userId(), document)).isFalse();
        moduleStates.remove(WmsModule.ID);
        assertThat(allowed(service)).isFalse();
    }
    @Test void unavailableIdentityVisibilityPermissionOrModulePolicyFailsClosed() {
        var service = authorization(new DocumentAccessPolicy() {
            public String module() { return WmsModule.ID; }
            public boolean allowed(UserIdentity user, DocumentReference document) { throw new IllegalStateException(); }
        });
        assertThat(allowed(service)).isFalse();
        assertThat(new DocumentAuthorization(modules, states, id -> { throw new IllegalStateException(); },
                List.of(functional), visibility, List.of(wms)).allowed(USER.userId(), DOCUMENT)).isFalse();
        assertThat(new DocumentAuthorization(modules, states, id -> Optional.of(USER),
                List.of((user, permission) -> { throw new IllegalStateException(); }), visibility, List.of(wms))
                .allowed(USER.userId(), DOCUMENT)).isFalse();
        assertThat(new DocumentAuthorization(modules, states, id -> Optional.of(USER), List.of(functional),
                (user, document) -> { throw new IllegalStateException(); }, List.of(wms)).allowed(USER.userId(), DOCUMENT)).isFalse();
    }
    @Test void relationHidesEntireEdgeForEitherRestrictedEndpoint() {
        var service = authorization(wms, policy(OTHER_MODULE, true));
        var outgoing = new DocumentAuthorization.Relation(DOCUMENT, OTHER);
        var incoming = new DocumentAuthorization.Relation(OTHER, DOCUMENT);
        assertThat(service.visibleRelations(USER.userId(), List.of(outgoing, incoming))).containsExactly(outgoing, incoming);
        hiddenDocuments.add(OTHER.id());
        assertThat(service.visibleRelations(USER.userId(), List.of(outgoing, incoming))).isEmpty();
        hiddenDocuments.clear();
        hiddenDocuments.add(DOCUMENT.id());
        assertThat(service.visibleRelations(USER.userId(), List.of(outgoing, incoming))).isEmpty();
    }
    @Test void relationRechecksMembershipIdentityAndEachOwnerOnNextQuery() {
        var service = authorization(wms, policy(OTHER_MODULE, true));
        var edge = new DocumentAuthorization.Relation(DOCUMENT, OTHER);
        assertThat(service.visibleRelations(USER.userId(), List.of(edge))).containsExactly(edge);
        memberships.clear();
        assertThat(service.visibleRelations(USER.userId(), List.of(edge))).isEmpty();
        memberships.put(USER.userId(), Set.of(REQUIRED_DEPARTMENT));
        states.disable(OTHER_MODULE);
        assertThat(service.visibleRelations(USER.userId(), List.of(edge))).isEmpty();
        states.enable(OTHER_MODULE);
        currentUser = null;
        assertThat(service.visibleRelations(USER.userId(), List.of(edge))).isEmpty();
    }
    @Test void invalidRegistrationAndMissingVisibilityCannotEnableAuthorization() {
        assertThatThrownBy(() -> authorization(policy("unavailable.module", true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentAuthorization(modules, states, id -> Optional.of(USER),
                List.of(functional), null, List.of(wms))).isInstanceOf(NullPointerException.class);
        assertThat(authorization(wms).allowed(USER.userId(), null)).isFalse();
    }
    @Test void trustedReferencesRequireFunctionalPermissionsAndDefensiveCopy() {
        assertThatThrownBy(() -> new DocumentReference(DOCUMENT_ID, WmsModule.ID, Set.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentReference(" ", WmsModule.ID, DOCUMENT.requiredPermissions(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentReference(DOCUMENT_ID, WmsModule.ID, Set.of(" "), false))
                .isInstanceOf(IllegalArgumentException.class);
        var permissions = new HashSet<>(DOCUMENT.requiredPermissions());
        var document = new DocumentReference(DOCUMENT_ID, WmsModule.ID, permissions, true);
        permissions.clear();
        assertThat(document.requiredPermissions()).containsExactly(WmsCapabilities.UOMS_READ);
        assertThatThrownBy(() -> document.requiredPermissions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
