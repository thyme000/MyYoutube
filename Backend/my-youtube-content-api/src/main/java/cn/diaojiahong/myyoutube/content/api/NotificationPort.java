package cn.diaojiahong.myyoutube.content.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容服务对外契约: 站内通知写入
 */
@FeignClient(name = "my-youtube-content-service", contextId = "notificationPort",
        path = "/internal/notification", url = "${my-youtube.content-service.url:}")
public interface NotificationPort {

    @PostMapping("/create")
    void createNotification(@RequestBody NotificationCreate create);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class NotificationCreate {
        private Long receiverId;
        private Long senderId;
        private String type;
        private String content;
        private String refType;
        private Long refId;
        private String extraJson;
    }
}
