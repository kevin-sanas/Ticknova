package com.ticknova.identity;

import com.ticknova.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    private final LoginRateLimiter limiter;
    public AuthController(AuthService auth,LoginRateLimiter limiter) { this.auth=auth; this.limiter=limiter; }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        var login=auth.login(body.username(),body.password());
        return new LoginResponse(login.token(),1800,login.expiresAt(),login.user());
    }

    @GetMapping({"/me","/auth/me"}) public Actor me() { return CurrentUser.get(); }

    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) { auth.logout(CurrentUser.get(),request.getHeader("Authorization").substring(7)); }

    @PostMapping("/auth/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body) { auth.changePassword(CurrentUser.get(),body.oldPassword(),body.newPassword()); }

    public record LoginRequest(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=256) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max=256) String oldPassword,@NotBlank @Size(min=12,max=72) String newPassword) {}
    public record LoginResponse(String accessToken,long expiresIn,Instant expiresAt,Actor user) {}
}
