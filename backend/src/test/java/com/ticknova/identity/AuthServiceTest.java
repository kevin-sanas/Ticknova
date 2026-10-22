package com.ticknova.identity;

import com.ticknova.audit.AuditService;
import com.ticknova.shared.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final String password = UUID.randomUUID().toString();

    @Test void successfulLoginReturnsAnOpaqueTokenAndFirstPasswordFlag() {
        var repo = mock(IdentityRepository.class);
        UUID id = UUID.randomUUID();
        var user = new IdentityRepository.Account(id,"alice","Alice",true,true,encoder.encode(password));
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(repo.roles(id)).thenReturn(Set.of("REQUESTER"));
        when(repo.createSessionIfPasswordCurrent(eq(id),eq(user.passwordHash()),anyString(),any())).thenReturn(true);
        var service = auth(repo);
        var login = service.login("alice",password);
        assertThat(login.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(login.user().mustChangePassword()).isTrue();
        assertThat(login.user().roles()).containsExactly("REQUESTER");
        assertThat(Duration.between(Instant.now(),login.expiresAt()).toMinutes()).isBetween(29L,30L);
    }

    @Test void inactiveUserAndWrongPasswordReceiveSameSafeFailure() {
        var repo = mock(IdentityRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findByUsername("inactive")).thenReturn(Optional.of(new IdentityRepository.Account(id,"inactive","Inactive",false,false,encoder.encode(password))));
        when(repo.findByUsername("alice")).thenReturn(Optional.of(new IdentityRepository.Account(id,"alice","Alice",true,false,encoder.encode(password))));
        var service = auth(repo);
        for (String name : List.of("inactive","alice","unknown")) {
            assertThatThrownBy(() -> service.login(name,UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(ApiException.class,e -> { assertThat(e.status()).isEqualTo(401); assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"); });
        }
    }

    @Test void passwordChangeEnforcesLengthAndDoesNotAcceptTheOldPasswordAsNew() {
        var repo = mock(IdentityRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(new IdentityRepository.Account(id,"alice","Alice",true,true,encoder.encode(password))));
        var actor = new com.ticknova.shared.Actor(id,"alice","Alice",Set.of("REQUESTER"),true);
        var service = auth(repo);
        assertThatThrownBy(() -> service.changePassword(actor,password,"short"))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> service.changePassword(actor,password,password))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test void aPasswordChangedDuringLoginCannotIssueANewToken() {
        var repo = mock(IdentityRepository.class);
        var user = new IdentityRepository.Account(UUID.randomUUID(),"alice","Alice",true,false,encoder.encode(password));
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(repo.roles(user.id())).thenReturn(Set.of("REQUESTER"));
        when(repo.createSessionIfPasswordCurrent(eq(user.id()),eq(user.passwordHash()),anyString(),any())).thenReturn(false);
        assertThatThrownBy(() -> auth(repo).login("alice",password))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test void oversizedBcryptInputReceivesASafeCredentialFailure() {
        var repo=mock(IdentityRepository.class);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(new IdentityRepository.Account(UUID.randomUUID(),"alice","Alice",true,false,encoder.encode(password))));
        assertThatThrownBy(() -> auth(repo).login("alice","界".repeat(25)))
            .isInstanceOfSatisfying(ApiException.class,e -> { assertThat(e.status()).isEqualTo(401); assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"); });
    }

    private AuthService auth(IdentityRepository repo) {
        return new AuthService(repo,new SessionTokens("test-only-pepper-"+UUID.randomUUID()),encoder,mock(AuditService.class));
    }
}
