package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.mapper.VideoCommentMapper;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 视频评论表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@Service
public class VideoCommentServiceImpl extends ServiceImpl<VideoCommentMapper, VideoComment> implements IVideoCommentService {

}
