package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;

import java.util.List;

/**
 * 内容打标策略: 信号加载/结果落库/ES 全量文档组装按内容类型分派,
 * 打标公共流程(LLM 调用/防线/关联写入/引用计数)在 AutoTagService。
 */
public interface ContentTaggingStrategy {

    ContentTypeEnum type();

    /** 加载打标信号(标题+简介), 内容不存在/已删/无有效文本返回 null */
    TagSignal loadSignal(Long contentId);

    /** 机器打标结果落库: 类目回写 + tags 展示缓存回写 */
    void persistResult(Long contentId, String categoryCode, String tagsCache);

    /**
     * 组装该内容当前状态的 ES 全量文档消息(打标链路是 content 文档唯一全量写者)。
     * 内容已被删除返回 null, 跳过同步。
     */
    ContentDocMessage buildEsMessage(Long contentId, List<Long> tagIds);

    record TagSignal(String title, String description) {
    }
}
