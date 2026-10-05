package cn.diaojiahong.myyoutube.chat.service.strategy;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;

import java.util.Set;

/**
 * 聊天消息内容类型策略: 校验/快照/URL签名/会话预览 按类型分派,
 * 新增消息类型只需加一个实现类, 不再往 ChatMessageService 里加平行分支。
 */
public interface ChatContentTypeStrategy {

    /** 本策略承接的内容类型, 一个实现可承接多个(如 IMAGE/FILE 共用媒体语义) */
    Set<ChatContentTypeEnum> types();

    /** 发送前校验, 不合法抛 BizException */
    void validate(ChatContent content);

    /** 发送时构建落库内容: 转发类查远端拼不可变快照卡片, 其余原样透传 */
    default ChatContent snapshot(ChatContent content) {
        return content;
    }

    /** 就地签名 content 内的 objectKey(读取路径用), 无媒体字段则不处理 */
    default void signUrls(ChatContent content, long expireSeconds) {
    }

    /** 会话列表最后一条消息的预览文案, 无预览返回 null */
    default String previewLabel(ChatContent content) {
        return null;
    }
}
