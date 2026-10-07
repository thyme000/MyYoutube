package cn.diaojiahong.myyoutube.common.idempotent;

/**
 * 重复请求的处置策略
 */
public enum DuplicatePolicy {

    /** 抛 BizException, 适合 HTTP 防重复提交 */
    THROW,

    /** 静默吞掉记日志, 适合 MQ 重复投递 —— 抛异常会触发重试甚至死信 */
    SKIP,

    /** 返回上次成功结果(结果 JSON 回放, 丢失泛型, 适合简单返回类型) */
    REPLAY
}
