package cn.diaojiahong.myyoutube.video.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * video 服务对外契约: 转发卡片/分享计数等内部操作
 */
@FeignClient(name = "my-youtube-video-service", contextId = "videoInternalPort",
        path = "/internal/video", url = "${my-youtube.video-service.url:}")
public interface VideoInternalPort {

    /** 转发视频卡片信息, 视频不存在返回 null */
    @GetMapping("/forward-info/{videoId}")
    ForwardVideoInfo getForwardVideo(@PathVariable("videoId") Long videoId);

    /** 分享数 +1, 仅对已发布且公开的视频生效 */
    @PostMapping("/share/{videoId}")
    boolean incrementShareCount(@PathVariable("videoId") Long videoId);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ForwardVideoInfo {
        private Long videoId;
        private String title;
        private String coverUrl;
    }
}
