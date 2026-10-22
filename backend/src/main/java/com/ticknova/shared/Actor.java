package com.ticknova.shared;

import java.util.Set;
import java.util.UUID;

public record Actor(UUID id, String username, String displayName, Set<String> roles, boolean mustChangePassword) {
    public boolean hasRole(String role) { return roles.contains(role); }
    public void requireRole(String role) {
        if (!hasRole(role)) throw new ApiException(403, "FORBIDDEN", "当前角色无权执行此操作");
    }
}
