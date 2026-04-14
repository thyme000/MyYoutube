package cn.diaojiahong.myyoutube.common.constants;

public class RabbitMqConstants {

    // 生成视频封面队列
    public static final String VIDEO_COVER_QUEUE = "video_cover_queue";
    // 生成视频封面路由键
    public static final String VIDEO_COVER_ROUTING_KEY = "video_cover_routing_key";
    // 生成视频封面交换机
    public static final String VIDEO_COVER_EXCHANGE = "video_cover_exchange";

    // 更新视频文档队列
    public static final String UPDATE_VIDEO_DOCUMENT_QUEUE = "update_video_document_queue";
    // 更新视频文档路由键
    public static final String UPDATE_VIDEO_DOCUMENT_ROUTING_KEY = "update_video_document_routing_key";
    // 更新视频文档交换机
    public static final String UPDATE_VIDEO_DOCUMENT_EXCHANGE = "update_video_document_exchange";

    // 点赞评论队列
    public static final String LIKE_COMMENT_QUEUE = "like_comment_queue";
    // 点赞评论路由键
    public static final String LIKE_COMMENT_ROUTING_KEY = "like_comment_routing_key";
    // 点赞评论交换机
    public static final String LIKE_COMMENT_EXCHANGE = "like_comment_exchange";

    // 取消点赞评论队列
    public static final String UNLIKE_COMMENT_QUEUE = "unlike_comment_queue";
    // 取消点赞评论路由键
    public static final String UNLIKE_COMMENT_ROUTING_KEY = "unlike_comment_routing_key";
    // 取消点赞评论交换机
    public static final String UNLIKE_COMMENT_EXCHANGE = "unlike_comment_exchange";

    // 发布评论队列
    public static final String PUBLISH_COMMENT_QUEUE = "publish_comment_queue";
    // 发布评论路由键
    public static final String PUBLISH_COMMENT_ROUTING_KEY = "publish_comment_routing_key";
    // 发布评论交换机
    public static final String PUBLISH_COMMENT_EXCHANGE = "publish_comment_exchange";

    // 更新用户文档队列
    public static final String UPDATE_USER_DOCUMENT_QUEUE = "update_user_document_queue";
    // 更新用户文档路由键
    public static final String UPDATE_USER_DOCUMENT_ROUTING_KEY = "update_user_document_routing_key";
    // 更新用户文档交换机
    public static final String UPDATE_USER_DOCUMENT_EXCHANGE = "update_user_document_exchange";

    // 发送消息队列
    public static final String MESSAGE_QUEUE = "message_queue";
    // 发送消息路由键
    public static final String MESSAGE_ROUTING_KEY = "message_routing_key";
    // 发送消息交换机
    public static final String MESSAGE_EXCHANGE = "message_exchange";

    // 已读消息队列
    public static final String READ_MESSAGE_QUEUE = "read_message_queue";
    // 已读消息路由键
    public static final String READ_MESSAGE_ROUTING_KEY = "read_message_routing_key";
    // 已读消息交换机
    public static final String READ_MESSAGE_EXCHANGE = "read_message_exchange";

    // 通知群成员会话已删除队列
    public static final String NOTIFY_GROUP_MEMBER_DELETED_QUEUE = "notify_group_member_deleted_queue";
    // 通知群成员会话已删除路由键
    public static final String NOTIFY_GROUP_MEMBER_DELETED_ROUTING_KEY = "notify_group_member_deleted_routing_key";
    // 通知群成员会话已删除交换机
    public static final String NOTIFY_GROUP_MEMBER_DELETED_EXCHANGE = "notify_group_member_deleted_exchange";

    // 合并图片为视频队列
    public static final String MERGE_PICTURES_QUEUE = "merge_pictures_queue";
    // 合并图片为视频路由键
    public static final String MERGE_PICTURES_ROUTING_KEY = "merge_pictures_routing_key";
    // 合并图片为视频交换机
    public static final String MERGE_PICTURES_EXCHANGE = "merge_pictures_exchange";
}
