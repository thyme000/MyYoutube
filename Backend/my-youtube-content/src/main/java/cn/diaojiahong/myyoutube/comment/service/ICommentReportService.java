package cn.diaojiahong.myyoutube.comment.service;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReportDTO;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import com.baomidou.mybatisplus.extension.service.IService;

public interface ICommentReportService extends IService<CommentReport> {

    void reportComment(CommentReportDTO commentReportDTO);
}
