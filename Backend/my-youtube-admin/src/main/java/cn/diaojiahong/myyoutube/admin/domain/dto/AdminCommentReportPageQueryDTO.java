package cn.diaojiahong.myyoutube.admin.domain.dto;

import cn.diaojiahong.myyoutube.video.api.enums.CommentReportReasonEnum;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class AdminCommentReportPageQueryDTO extends PageQuery {
    private CommentReportReasonEnum reasonCode;
    private Long reporterId;
}
