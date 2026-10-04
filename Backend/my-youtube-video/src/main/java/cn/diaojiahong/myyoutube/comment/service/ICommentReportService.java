package cn.diaojiahong.myyoutube.comment.service;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReportDTO;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 评论举报信息表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-14
 */
public interface ICommentReportService extends IService<CommentReport> {

    void reportComment(CommentReportDTO commentReportDTO);
}
