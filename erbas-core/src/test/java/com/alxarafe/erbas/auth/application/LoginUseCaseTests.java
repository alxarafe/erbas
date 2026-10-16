package com.alxarafe.erbas.auth.application;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LoginUseCaseTests {

    @Test
    void unknownUserStillPerformsPasswordVerificationAndNeverIssuesToken() {
        var users = mock(LoginUseCase.CredentialLookup.class);
        var passwords = mock(LoginUseCase.PasswordVerifier.class);
        var tokens = mock(LoginUseCase.TokenIssuer.class);
        when(users.findUserByEmail("absent")).thenReturn(Optional.empty());
        assertThat(new LoginUseCase(users, passwords, tokens).login("absent", "unchanged ")).isEmpty();
        verify(passwords).matches("unchanged ", null);
        verifyNoInteractions(tokens);
    }

    @Test
    void disabledUserIsCheckedButCannotReceiveTokenEvenWithMatchingPassword() {
        var users = mock(LoginUseCase.CredentialLookup.class);
        var passwords = mock(LoginUseCase.PasswordVerifier.class);
        var tokens = mock(LoginUseCase.TokenIssuer.class);
        when(users.findUserByEmail("disabled"))
                .thenReturn(Optional.of(new LoginUseCase.Credentials(12, "disabled", "encoded", false)));
        when(passwords.matches("raw", "encoded")).thenReturn(true);
        assertThat(new LoginUseCase(users, passwords, tokens).login("disabled", "raw")).isEmpty();
        verify(passwords).matches("raw", "encoded");
        verifyNoInteractions(tokens);
    }
}
