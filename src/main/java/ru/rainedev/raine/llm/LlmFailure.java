package ru.rainedev.raine.llm;

/** Status-bearing endpoint error; response bodies never need to be parsed by callers. */
public final class LlmFailure extends IllegalStateException {
    private final int status;
    private final boolean requiresOperator;
    private final boolean retryable;

    public LlmFailure(int status, String message) {
        this(status, message, status == 401 || status == 402 || status == 403);
    }

    LlmFailure(int status, String message, boolean requiresOperator) {
        this(status, message, requiresOperator, status == 408 || status == 429 || status >= 500);
    }

    LlmFailure(int status, String message, boolean requiresOperator, boolean retryable) {
        super(message);
        this.status = status;
        this.requiresOperator = requiresOperator;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public boolean retryable() { return retryable; }
    public boolean requiresOperator() { return requiresOperator; }
}
