package cn.diaojiahong.myyoutube.comment.domain.po;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportReasonEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentReportStatusEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 评论举报信息表
 * </p>
 *
 * @author thyme
 * @since 2026-04-14
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("comment_report")
public class CommentReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 被举报的评论ID
     */
    private Long commentId;

    /**
     * 举报人的用户ID
     */
    private Long reporterUserId;

    /**
     * 举报原因编码：辱骂/涉黄/广告/引战/其他
     */
    private CommentReportReasonEnum reasonCode;

    /**
     * 补充说明（可选），默认空字符串
     */
    private String remark;

    /**
     * 处理状态：PENDING待处理/ACCEPTED已通过/REJECTED已驳回
     */
    private CommentReportStatusEnum status;

    /**
     * 创建时间，默认当前时间
     */
    private LocalDateTime createdAt;


}
