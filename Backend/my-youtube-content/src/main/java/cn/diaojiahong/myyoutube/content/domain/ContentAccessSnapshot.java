package cn.diaojiahong.myyoutube.content.domain;

import cn.diaojiahong.myyoutube.content.domain.po.Content;

import java.util.Objects;

/**
 * 内容与关联音频的共享快照，不保存用户访问结论；展示字段同样受状态 TTL 限制。
 * 载荷随 Content 表结构演进；滚动发布可能读到缺失字段，窗口可持续到旧结构最后一次回填后的 TTL 到期。
 * audioBlocked 使用 Boolean 检测缺失字段：缺失或 null 会使反序列化失败并回源，不能静默当作 false。
 */
public record ContentAccessSnapshot(Content content, Boolean audioBlocked) {
    public ContentAccessSnapshot {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(audioBlocked, "audioBlocked");
    }
}
