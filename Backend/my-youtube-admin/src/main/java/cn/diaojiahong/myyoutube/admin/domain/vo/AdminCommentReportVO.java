package cn.diaojiahong.myyoutube.admin.domain.vo;

import cn.diaojiahong.myyoutube.content.api.enums.CommentReportReasonEnum;
import cn.diaojiahong.myyoutube.content.api.enums.CommentReportStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentReportVO {
    private Long commentId;
    private String content;
    private Long reporterUserId;
    private String remark;
    private CommentReportReasonEnum reportType;
    private CommentReportStatusEnum status;
    private LocalDateTime createTime;
}
