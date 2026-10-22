package com.ticknova.shared;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > 1_000_000)
            throw new ApiException(400, "INVALID_PAGE", "分页范围无效");
    }
}
