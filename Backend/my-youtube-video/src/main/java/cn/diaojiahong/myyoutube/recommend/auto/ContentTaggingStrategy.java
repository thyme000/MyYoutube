package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;

import java.util.List;

/**
 * 内容打标策略: LLM 输入组装/结果落库/ES 全量文档组装按内容类型分派。
 * 各类型决定给 LLM 看什么证据——视频: 标题简介+截帧, 图集: 标题简介+选图, 笔记: 全文,
 * 编排层(AutoTagService)只负责 system 规则/词库/防线/落库, 不感知素材形态。
 */
public interface ContentTaggingStrategy {

    ContentTypeEnum type();

    /**
     * 组装打标的用户消息(文本与图片自由组合)。
     * 内容不存在/已删/无可用素材返回 null, 编排层跳过。
     */
    UserInput buildUserInput(Long contentId);

    /** 机器打标结果落库: 类目回写 + tags 展示缓存回写 */
    void persistResult(Long contentId, String categoryCode, String tagsCache);

    /**
     * 组装该内容当前状态的 ES 全量文档消息(打标链路是 content 文档唯一全量写者)。
     * 内容已被删除返回 null, 跳过同步。
     */
    ContentDocMessage buildEsMessage(Long contentId, List<Long> tagIds);

    /** 打标用户消息: 文本+图片, 允许纯文本或纯图片 */
    record UserInput(String text, List<Image> images) {

        public static UserInput ofText(String text) {
            return new UserInput(text, List.of());
        }
    }

    /** 图片素材: base64 内嵌(云端 LLM 拉不到内网 URL) */
    record Image(String mimeType, String base64Data) {
    }
}
