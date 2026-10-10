package com.alxarafe.erbas.auth.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Current user operations; credentials never become part of the public identity. */
public final class UserAdministration {
    private final Store store;
    private final Function<String, String> encodePassword;

    public UserAdministration(Store store, Function<String, String> encodePassword) {
        this.store = store;
        this.encodePassword = encodePassword;
    }

    public List<UserIdentity> list() { return store.listUsers(); }

    public Optional<UserIdentity> find(String id) {
        var internalId = internalId(id);
        return internalId == null ? Optional.empty() : store.findUserById(internalId);
    }

    public CreateResult create(String email, String password, boolean admin) {
        int length = password.codePointCount(0, password.length());
        if (length < 12 || length > 256) {
            return new CreateResult(CreateOutcome.INVALID_REQUEST, null);
        }
        return store.createUserIfEmailAvailable(email, encodePassword.apply(password), admin)
                .map(user -> new CreateResult(CreateOutcome.CREATED, user))
                .orElseGet(() -> new CreateResult(CreateOutcome.EMAIL_CONFLICT, null));
    }

    public UserUpdateResult update(String id, Boolean enabled, Boolean admin) {
        var internalId = internalId(id);
        return internalId == null ? new UserUpdateResult(UserUpdateOutcome.NOT_FOUND, null)
                : store.updateUserState(internalId, enabled, admin);
    }

    private static Long internalId(String id) {
        try {
            long value = Long.parseLong(id);
            return value > 0 ? value : null;
        } catch (NumberFormatException unusable) {
            return null;
        }
    }

    public interface Store {
        List<UserIdentity> listUsers();
        Optional<UserIdentity> findUserById(long id);
        Optional<UserIdentity> createUserIfEmailAvailable(String email, String passwordHash, boolean admin);
        UserUpdateResult updateUserState(long id, Boolean enabled, Boolean admin);
    }

    public enum CreateOutcome { CREATED, INVALID_REQUEST, EMAIL_CONFLICT }
    public record CreateResult(CreateOutcome outcome, UserIdentity user) { }
    public enum UserUpdateOutcome { UPDATED, NOT_FOUND, LAST_ADMIN }
    public record UserUpdateResult(UserUpdateOutcome outcome, UserIdentity user) { }
}
