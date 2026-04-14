package cn.diaojiahong.myyoutube.admin.domain.vo;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportReasonEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentReportVO {
    // 评论id
    private Long commentId;
    // 评论正文
    private String content;
    // 举报人id
    private Long reporterUserId;
    // 举报描述
    private String remark;
    // 举报原因
    private CommentReportReasonEnum reportType;
    // 举报状态
    private CommentReportStatusEnum status;
    // 举报时间
    private LocalDateTime createTime;
}
