package com.ticknova.identity;

import com.ticknova.shared.ApiException;
import java.nio.charset.StandardCharsets;

final class PasswordPolicy {
    private PasswordPolicy() {}
    static void validate(String password) {
        if (password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ApiException(400,"INVALID_PASSWORD","密码至少 12 个字符，且 UTF-8 长度不超过 72 字节");
    }
}
