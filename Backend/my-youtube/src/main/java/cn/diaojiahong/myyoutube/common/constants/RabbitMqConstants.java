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
}
