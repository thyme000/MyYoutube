package cn.diaojiahong.myyoutube.common.push;

import lombok.Data;

/**
 * 推送扇出信封, 经 Redis pub/sub 广播到所有实例, 由订阅端按频道分发:
 * WS 频道取 payload，SSE 频道取 signal 及可选的 payload。
 */
@Data
public class PushEnvelope {

    private Long userId;
    private String signal;

    private Object payload;
}
