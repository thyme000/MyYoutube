package cn.diaojiahong.myyoutube.common.resilience;

import java.util.Set;

public final class ModelUnavailable extends IllegalStateException {
    public static final Set<String> ADMISSION_CODES = Set.of("MODEL_RECOVERING", "MODEL_RECOVERY_EXHAUSTED",
            "MODEL_QUEUE_FULL", "MODEL_QUEUE_TIMEOUT", "MODEL_SHUTTING_DOWN");

    public static final Set<String> DEFER_CODES = Set.of("MODEL_RECOVERING", "MODEL_QUEUE_FULL",
            "MODEL_QUEUE_TIMEOUT", "MODEL_SHUTTING_DOWN", "MODEL_ADMISSION_BLOCKED");

    private final String resource;
    private final String code;
    private final int retryAfterSeconds;

    public ModelUnavailable(String code, int retryAfterSeconds, Throwable cause) {
        this(null, code, retryAfterSeconds, cause);
    }

    public ModelUnavailable(String resource, String code, int retryAfterSeconds, Throwable cause) {
        super(code, cause);
        this.resource = resource;
        this.code = code;
        this.retryAfterSeconds = Math.max(5, Math.min(60, retryAfterSeconds));
    }

    public String resource() {
        return resource;
    }

    public boolean terminal() {
        return Set.of("MODEL_CIRCUIT_OPEN", "MODEL_RECOVERY_EXHAUSTED").contains(code);
    }

    public ModelUnavailable forResource(String value) {
        return resource == null ? new ModelUnavailable(value, code, retryAfterSeconds, this) : this;
    }

    public String code() {
        return code;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int status() {
        return 503;
    }
}
