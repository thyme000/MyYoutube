package cn.diaojiahong.myyoutube.common.constants;

public class MqConstants {
    public static final String TOPIC_PIPELINE_EVENTS = "pipeline_event_topic";
    public static final String TOPIC_CONTENT_SUBMITTED = "content_submitted_topic";
    public static final String TOPIC_CONTENT_UNDERSTOOD = "content_understood_topic";

    public static final String TOPIC_REVIEW_REQUESTED = "review_requested_topic";
    public static final String TOPIC_REVIEW_RESULT = "review_result_topic";
    public static final String TOPIC_PUBLICATION_EFFECT = "publication_effect_topic";

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
    // 动态打标任务, video 服务自产自销
    public static final String TOPIC_AUTO_TAG = "auto_tag_topic";
    // 上传事件(对象存储 webhook 桥接后拆任务), transcode 自产自销
    public static final String TOPIC_MEDIA_UPLOAD_EVENT = "media_upload_event_topic";
    // 媒体处理任务分发, 编排器 -> worker
    public static final String TOPIC_MEDIA_TASK = "media_task_topic";

    public static final String TAG_LIKE = "like";
    public static final String TAG_UNLIKE = "unlike";
    public static final String TAG_PUBLISH = "publish";
    public static final String TAG_ES_USER = "user";
    public static final String TAG_ES_CONTENT = "video";
}
