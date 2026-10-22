package com.ticknova.identity;

import com.ticknova.shared.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerSessionFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(BearerSessionFilter.class);
    private static final Set<String> PASSWORD_ROUTES=Set.of("/api/v1/me","/api/v1/auth/me","/api/v1/auth/logout","/api/v1/auth/change-password");
    private final AuthService auth;
    private final SecurityProblems problems;
    private final LinkedHashMap<String,RejectionWindow> rejectionWindows=new LinkedHashMap<>();
    public BearerSessionFilter(AuthService auth,SecurityProblems problems) { this.auth=auth; this.problems=problems; }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            if (request.getRequestURI().startsWith("/api/v1/") && !request.getRequestURI().equals("/api/v1/auth/login")) auditRejected(request.getRemoteAddr());
            chain.doFilter(request,response); return;
        }
        try {
            String header=headers.getFirst();
            if (headers.size()!=1 || !header.startsWith("Bearer ")) throw new ApiException(401,"UNAUTHENTICATED","请重新登录");
            var actor=auth.authenticate(header.substring(7));
            if (actor.mustChangePassword() && !PASSWORD_ROUTES.contains(request.getRequestURI()))
                throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","首次登录请先修改密码");
            var authorities=actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_"+role)).toList();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,authorities));
        } catch (ApiException error) {
            SecurityContextHolder.clearContext();
            if (error.status()==401) auditRejected(request.getRemoteAddr());
            problems.write(request,response,error.status(),error.code(),error.getMessage());
            return;
        } catch (RuntimeException error) {
            SecurityContextHolder.clearContext();
            problems.write(request,response,503,"AUTH_UNAVAILABLE","认证服务暂时不可用，请稍后重试");
            return;
        }
        chain.doFilter(request,response);
    }

    private void auditRejected(String peer) {
        if (allowAudit(peer)) {
            try { auth.authenticationRejected(); }
            catch (RuntimeException unavailable) { log.warn("Authentication rejection audit unavailable"); }
        }
    }

    private synchronized boolean allowAudit(String peer) {
        long now=System.currentTimeMillis();
        rejectionWindows.entrySet().removeIf(e -> now-e.getValue().startedAt>=60_000);
        var window=rejectionWindows.get(peer);
        if (window==null) {
            if (rejectionWindows.size()>=4096) rejectionWindows.remove(rejectionWindows.keySet().iterator().next());
            window=new RejectionWindow(now); rejectionWindows.put(peer,window);
        }
        if (window.count>=30) return false;
        window.count++; return true;
    }
    private static final class RejectionWindow {
        final long startedAt; int count;
        RejectionWindow(long startedAt) { this.startedAt=startedAt; }
    }
}
