package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * video 服务对外契约: 管理端视频操作
 */
@FeignClient(name = "my-youtube-video-service", contextId = "videoAdminPort",
        path = "/internal/admin/video", url = "${my-youtube.video-service.url:}")
public interface VideoAdminPort {

    @PostMapping("/mark-status")
    Result<Boolean> markStatus(@RequestBody MarkStatus mark);

    @PostMapping("/get")
    Result<VideoSummary> getVideo(@RequestBody VideoId id);

    @PostMapping("/page")
    Result<PageResult<VideoSummary>> pageVideos(@RequestBody AdminVideoQuery query);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class VideoId {
        private Long videoId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class MarkStatus {
        private Long videoId;
        private String statusCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class AdminVideoQuery {
        private String statusCode;
        private String categoryCode;
        private String typeCode;
        private Long userId;
        private long pageNo;
        private long pageSize;
    }
}
