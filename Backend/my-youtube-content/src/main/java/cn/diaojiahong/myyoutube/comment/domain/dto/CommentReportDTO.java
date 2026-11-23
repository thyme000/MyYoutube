package cn.diaojiahong.myyoutube.comment.domain.dto;

import cn.diaojiahong.myyoutube.content.api.enums.CommentReportReasonEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CommentReportDTO {
    @NotNull(message = "评论id不能为空")
    private Long commentId;
    @NotNull(message = "举报原因不能为空")
    private CommentReportReasonEnum reportType;
    @NotBlank(message = "举报描述不能为空")
    private String remark;
}
