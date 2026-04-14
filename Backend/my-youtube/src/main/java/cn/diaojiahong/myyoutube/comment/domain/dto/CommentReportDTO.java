package cn.diaojiahong.myyoutube.comment.domain.dto;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportReasonEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CommentReportDTO {
    @NotNull(message = "评论id不能为空")
    private Long commentId;
    // 举报原因
    @NotNull(message = "举报原因不能为空")
    private CommentReportReasonEnum reportType;
    // 举报描述
    @NotBlank(message = "举报描述不能为空")
    private String remark;
}
