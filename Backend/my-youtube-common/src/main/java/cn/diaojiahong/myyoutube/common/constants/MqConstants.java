package cn.diaojiahong.myyoutube.common.constants;

public class MqConstants {

    // 视频封面生成
    public static final String TOPIC_VIDEO_COVER = "video_cover_topic";
    // 封面生成结果回传: transcode -> video
    public static final String TOPIC_VIDEO_COVER_RESULT = "video_cover_result_topic";
    // 评论互动, tag 区分: like/unlike/publish
    public static final String TOPIC_COMMENT_ACTION = "comment_action_topic";
    // ES 文档同步, tag 区分: user
    public static final String TOPIC_ES_DOCUMENT = "es_document_topic";
    // 聊天消息(发送/撤回)
    public static final String TOPIC_CHAT_MESSAGE = "chat_message_topic";
    // 已读回执
    public static final String TOPIC_CHAT_READ = "chat_read_topic";
    // 群解散通知
    public static final String TOPIC_CHAT_GROUP_DELETED = "chat_group_deleted_topic";
    // 动态成片懒转码, 消息体只带 taskId
    public static final String TOPIC_DYNAMIC_TRANSCODE = "dynamic_transcode_topic";

    public static final String TAG_LIKE = "like";
    public static final String TAG_UNLIKE = "unlike";
    public static final String TAG_PUBLISH = "publish";
    public static final String TAG_ES_USER = "user";
    public static final String TAG_ES_VIDEO = "video";
}
