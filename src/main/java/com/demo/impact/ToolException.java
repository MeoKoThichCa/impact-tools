package com.demo.impact;

public final class ToolException extends Exception {
    private final int exitCode;
    public ToolException(int exitCode, String message) { super(message); this.exitCode = exitCode; }
    public ToolException(int exitCode, String message, Throwable cause) { super(message, cause); this.exitCode = exitCode; }
    public int exitCode() { return exitCode; }
}
