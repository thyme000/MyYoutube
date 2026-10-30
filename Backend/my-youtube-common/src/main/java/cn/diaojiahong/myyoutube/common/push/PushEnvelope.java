package cn.diaojiahong.myyoutube.common.push;

import lombok.Data;

/**
 * 推送扇出信封, 经 Redis pub/sub 广播到所有实例, 由订阅端按频道分发:
 * WS 频道取 payload(聊天事件报文本体), SSE 频道取 signal(门铃信号)。
 */
@Data
public class PushEnvelope {

    private Long userId;
    /** SSE 门铃信号: NOTIFY_DIRTY / CHAT_DIRTY */
    private String signal;

    /** WS 通道报文本体(原样下发前端) */
    private Object payload;
}
