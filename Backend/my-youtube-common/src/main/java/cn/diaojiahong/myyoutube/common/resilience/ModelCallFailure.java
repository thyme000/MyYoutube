package cn.diaojiahong.myyoutube.common.resilience;

public final class ModelCallFailure extends IllegalStateException {
    private final String resource;
    public ModelCallFailure(String resource, Throwable cause) {
        this(resource, "MODEL_EXECUTION_FAILED", cause);
    }
    public ModelCallFailure(String resource, String message, Throwable cause) {
        super(message,cause);
        this.resource=resource;
    }
    public String resource() { return resource; }
}
