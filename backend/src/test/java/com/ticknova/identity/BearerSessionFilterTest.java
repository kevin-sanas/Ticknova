package com.ticknova.identity;

import com.ticknova.shared.Actor;
import jakarta.servlet.FilterChain;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerSessionFilterTest {
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void firstPasswordSessionCannotReachBusinessData() throws Exception {
        var auth=mock(AuthService.class);
        String token="t".repeat(43);
        when(auth.authenticate(token)).thenReturn(new Actor(UUID.randomUUID(),"alice","Alice",Set.of("ADMIN"),true));
        var request=new MockHttpServletRequest("GET","/api/v1/users");
        request.addHeader("Authorization","Bearer "+token);
        var response=new MockHttpServletResponse();
        AtomicBoolean reached=new AtomicBoolean();
        new BearerSessionFilter(auth,new SecurityProblems(new ObjectMapper())).doFilter(request,response,(req,res)->reached.set(true));
        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED").doesNotContain(token);
    }

    @Test void firstPasswordSessionCanReachItsPasswordChangeRoute() throws Exception {
        var auth=mock(AuthService.class);
        String token="t".repeat(43);
        when(auth.authenticate(token)).thenReturn(new Actor(UUID.randomUUID(),"alice","Alice",Set.of("REQUESTER"),true));
        var request=new MockHttpServletRequest("POST","/api/v1/auth/change-password");
        request.addHeader("Authorization","Bearer "+token);
        var response=new MockHttpServletResponse();
        AtomicBoolean reached=new AtomicBoolean();
        new BearerSessionFilter(auth,new SecurityProblems(new ObjectMapper())).doFilter(request,response,(req,res)->reached.set(true));
        assertThat(reached).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(Actor.class);
    }

    @Test void duplicateAuthorizationHeadersFailClosed() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/v1/me");
        request.addHeader("Authorization","Bearer "+"a".repeat(43));
        request.addHeader("Authorization","Bearer "+"b".repeat(43));
        var response=new MockHttpServletResponse();
        new BearerSessionFilter(mock(AuthService.class),new SecurityProblems(new ObjectMapper())).doFilter(request,response,(req,res)->{ throw new AssertionError("unauthenticated request reached business handler"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
