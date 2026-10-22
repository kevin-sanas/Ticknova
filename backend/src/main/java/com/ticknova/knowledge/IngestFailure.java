package com.ticknova.knowledge;

public class IngestFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public IngestFailure(String code,boolean retryable) { super(code);this.code=code;this.retryable=retryable; }
    public String code(){return code;}
    public boolean retryable(){return retryable;}
}
