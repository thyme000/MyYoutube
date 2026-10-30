package cn.diaojiahong.myyoutube.common.observability;

@FunctionalInterface
public interface OutboxPublisher {
    void send(byte[] body) throws Exception;
}
