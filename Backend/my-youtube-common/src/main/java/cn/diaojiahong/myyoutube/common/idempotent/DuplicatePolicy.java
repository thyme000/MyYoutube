package cn.diaojiahong.myyoutube.common.idempotent;

/**
 * 重复请求的处置策略
 */
public enum DuplicatePolicy {

    /** 抛 BizException, 适合 HTTP 防重复提交 */
    THROW,

    /** 已成功则跳过；执行中抛异常，由 MQ 等调用方重试。 */
    SKIP,

    /** 返回上次成功结果(结果 JSON 回放, 丢失泛型, 适合简单返回类型) */
    REPLAY
}
