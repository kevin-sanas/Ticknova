package com.ticknova.ai.model;

/** Stable errors deliberately carry no provider response, prompt or underlying exception. */
public final class AiModelFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AiModelFailure(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
