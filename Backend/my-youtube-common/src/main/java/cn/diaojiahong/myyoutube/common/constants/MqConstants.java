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
    // 机器打标任务, video 服务自产自销
    public static final String TOPIC_AUTO_TAG = "auto_tag_topic";
    // 上传事件(对象存储 webhook 桥接后拆任务), transcode 自产自销
    public static final String TOPIC_MEDIA_UPLOAD_EVENT = "media_upload_event_topic";
    // 媒体处理任务分发, 编排器 -> worker
    public static final String TOPIC_MEDIA_TASK = "media_task_topic";
    // 任务完成回报, worker -> 编排器
    public static final String TOPIC_MEDIA_TASK_DONE = "media_task_done_topic";

    public static final String TAG_LIKE = "like";
    public static final String TAG_UNLIKE = "unlike";
    public static final String TAG_PUBLISH = "publish";
    public static final String TAG_ES_USER = "user";
    public static final String TAG_ES_CONTENT = "video";
}
