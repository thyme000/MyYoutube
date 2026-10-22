package cn.diaojiahong.myyoutube.transcode.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.transcode.config.MediaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 对象存储事件 webhook 桥接: RustFS ObjectCreated -> 规范化消息进 MQ。
 * 事件只当门铃, 业务语义(归属/幂等/拆任务)全部在消费者侧重建。
 * 经 compose 内网直连本服务, 网关已封 /internal/**; 共享密钥走 webhook URL 的 ?token=
 */
@Slf4j
@RestController
@RequestMapping("/internal/media")
@RequiredArgsConstructor
public class InternalStorageEventController {

    static final String VIDEO_KEY_PREFIX = "uploads/video/";

    private final MediaProperties mediaProperties;
    private final RocketMQTemplate rocketMQTemplate;

    @PostMapping("/storage-event")
    public void onStorageEvent(@RequestParam(value = "token", required = false) String token,
                               @RequestBody JsonNode body) {
        if (!mediaProperties.getWebhookToken().isEmpty()
                && !mediaProperties.getWebhookToken().equals(token)) {
            throw new BizException(ErrorCode.FORBIDDEN, "webhook token 校验失败");
        }
        JsonNode records = body.path("Records");
        if (!records.isArray()) {
            // 事件体可能含桶名/对象键等, 只记字段数, 不落原文
            log.warn("storage-event 缺少 Records 数组, 事件已忽略 bodyFields={}", body.isObject() ? body.size() : 0);
            return;
        }
        for (JsonNode record : records) {
            String eventName = record.path("eventName").asText(record.path("EventName").asText(""));
            String rawKey = record.path("s3").path("object").path("key").asText("");
            if (!eventName.startsWith("s3:ObjectCreated:") || rawKey.isEmpty()) {
                continue;
            }
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            // 只有视频目录进管线; 头像/聊天图/动态图不触发
            if (!key.startsWith(VIDEO_KEY_PREFIX)) {
                continue;
            }
            long size = record.path("s3").path("object").path("size").asLong(0);
            // 归属在消费者侧反查草稿, 事件本身不可信
            rocketMQTemplate.syncSend(MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, MessageBuilder
                    .withPayload(MediaUploadEventMessage.builder()
                            .objectKey(key)
                            .fileSize(size)
                            .build())
                    .build());
            log.info("已桥接上传事件 key={} size={}", key, size);
        }
    }
}
