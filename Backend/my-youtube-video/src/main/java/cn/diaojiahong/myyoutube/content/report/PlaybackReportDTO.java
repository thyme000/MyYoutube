package cn.diaojiahong.myyoutube.content.report;

import lombok.Data;

/**
 * 切走作品或离开播放页时结算的一次观看。登录账号、作品类型、作品总时长、会话、版本均由后端确定。
 * 同一内容的新快照由前端在发送前整份替换，旧观看无需补报。
 */
@Data
public class PlaybackReportDTO {

    /** 作品 ID */
    private Long contentId;

    /** 累计前台展示时长(毫秒): 内容实际在前台展示的时间 */
    private Long foregroundMs;

    /** 累计有效播放时长(毫秒): 前台展示中正常向前播放的时间, 校验 0 ≤ effectiveMs ≤ foregroundMs */
    private Long effectiveMs;

    /** 内容的续播位置（毫秒），必填，0 表示从头开始。 */
    private Long positionMs;

    /** 会话标识: 后端按账号惰性创建的 Redis 会话, 固定 TTL 不续期; 客户端传入一律被覆盖 */
    private String sessionId;

    /** 接收时间(毫秒): 端点盖章的服务器时间, 兼作快照的排序版本; 客户端传入一律被覆盖 */
    private Long receivedAt;
}
