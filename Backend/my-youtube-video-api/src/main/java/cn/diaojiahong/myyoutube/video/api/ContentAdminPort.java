package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * video 服务对外契约: 管理端视频操作
 */
@PropagateBusinessErrors({ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND, ErrorCode.STATE_CONFLICT})
@FeignClient(name = "my-youtube-video-service", contextId = "contentAdminPort",
        path = "/internal/admin/content", url = "${my-youtube.video-service.url:}")
public interface ContentAdminPort {

    @PostMapping("/media")
    cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO media(@RequestBody ContentId id);

    @PostMapping("/mark-status")
    Boolean markStatus(@RequestBody MarkStatus mark);

    @PostMapping("/page")
    PageResult<ContentSummary> pageContents(@RequestBody AdminContentQuery query);

    @PostMapping("/content/get")
    java.util.Map<String, Object> contentStatus(@RequestBody ContentId id);
    @PostMapping("/content/waiting")
    List<java.util.Map<String, Object>> waitingContent(@RequestBody AdminContentQuery query);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ContentId {
        private Long contentId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class MarkStatus {
        private Long contentId;
        private String statusCode;
        private String reason;
        private Long actorId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class AdminContentQuery {
        private String statusCode;
        private String categoryCode;
        private Long userId;
        private long pageNo;
        private long pageSize;
        private String type;
    }
}
