package com.ticknova.shared;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}
    public static Actor get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Actor actor))
            throw new ApiException(401, "UNAUTHENTICATED", "请重新登录");
        return actor;
    }
}
