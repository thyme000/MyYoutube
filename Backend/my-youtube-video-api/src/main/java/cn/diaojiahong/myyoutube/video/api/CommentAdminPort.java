package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;

/**
 * video 服务对外契约: 管理端评论/举报操作
 */
@FeignClient(name = "my-youtube-video-service", contextId = "commentAdminPort",
        path = "/internal/admin/comment", url = "${my-youtube.video-service.url:}")
public interface CommentAdminPort {

    @PostMapping("/block")
    Result<Void> block(@RequestBody CommentId id);

    @PostMapping("/restore")
    Result<Void> restore(@RequestBody CommentId id);

    @PostMapping("/page/blocked")
    Result<PageResult<CommentSummary>> pageBlockedComments(@RequestBody PageQuery q);

    @PostMapping("/report/ignore")
    Result<Void> ignoreReport(@RequestBody CommentId id);

    @PostMapping("/report/page")
    Result<PageResult<ReportSummary>> pageReports(@RequestBody ReportQuery q);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CommentId {
        private Long commentId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PageQuery {
        private long pageNo;
        private long pageSize;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ReportQuery {
        private String reasonCode;
        private Long reporterId;
        private long pageNo;
        private long pageSize;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CommentSummary {
        private Long commentId;
        private String content;
        private Long userId;
        private LocalDateTime createTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ReportSummary {
        private Long commentId;
        private String remark;
        private Long reporterUserId;
        private LocalDateTime createTime;
        private String content;
    }
}
