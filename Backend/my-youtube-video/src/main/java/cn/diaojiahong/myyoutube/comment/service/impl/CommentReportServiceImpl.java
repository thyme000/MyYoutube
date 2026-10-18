package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReportDTO;
import cn.diaojiahong.myyoutube.video.api.enums.CommentReportStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import cn.diaojiahong.myyoutube.comment.mapper.CommentReportMapper;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 评论举报信息表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-14
 */
@Service
public class CommentReportServiceImpl extends ServiceImpl<CommentReportMapper, CommentReport> implements ICommentReportService {

    @Override
    @ObservedBusiness(BusinessOperation.REPORT_COMMENT)
    public void reportComment(CommentReportDTO commentReportDTO) {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {

            throw new BizException(BizException.BIZ_ERROR, "用户未登录");
        }
        // 判断这个评论是否已经处理过了
        List<CommentReport> list = lambdaQuery()
                .eq(CommentReport::getCommentId, commentReportDTO.getCommentId())
                .eq(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .list();
        if (list != null && !list.isEmpty()) {

            return;
        }
        // 判断用户是否已经举报过了
        CommentReport one = lambdaQuery().eq(CommentReport::getReporterUserId, Long.parseLong(userId))
                .eq(CommentReport::getCommentId, commentReportDTO.getCommentId()).one();
        if (one != null) {

            // 只需要更新举报描述和举报原因
            one.setRemark(commentReportDTO.getRemark());
            one.setReasonCode(commentReportDTO.getReportType());
            one.setCreatedAt(LocalDateTime.now());
            updateById(one);
            return;
        } else {
            // 新增举报记录
            CommentReport commentReport = new CommentReport();
            commentReport.setReporterUserId(Long.parseLong(userId));
            commentReport.setCommentId(commentReportDTO.getCommentId());
            commentReport.setRemark(commentReportDTO.getRemark());
            commentReport.setStatus(CommentReportStatusEnum.PENDING);
            commentReport.setCreatedAt(LocalDateTime.now());
            commentReport.setReasonCode(commentReportDTO.getReportType());
            saveOrUpdate(commentReport);
            return;
        }
    }
}
