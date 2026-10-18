package cn.diaojiahong.myyoutube.observability;

public final class IngestFailure extends RuntimeException {
    public enum Reason { INVALID_BATCH, EXPIRED_BUCKET, MAPPING, CONFLICT, UNAVAILABLE }
    private final Reason reason;
    public IngestFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
    public boolean permanent() { return reason != Reason.UNAVAILABLE; }
}
