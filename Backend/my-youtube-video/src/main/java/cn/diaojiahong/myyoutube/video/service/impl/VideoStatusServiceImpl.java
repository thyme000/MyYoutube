package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.mybatis.OwnershipUpdate;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoStatusService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.*;

/**
 * 视频状态变更: 删除/私密/公开/评论开关, 变更后尽力同步 ES
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoStatusServiceImpl implements IVideoStatusService {

    private final VideoMapper videoMapper;
    private final IContentService contentService;
    private final ContentDocPort contentDocPort;

    @Transactional
    @Override
    public void deleteVideo(Long videoId) {
        Long userId = getUserId();
        OwnershipUpdate.updateOrFail(videoMapper, Video::getId, videoId, Video::getAuthorId, userId,
                w -> w.set(Video::getIsDelete, true), "删除失败,请稍后再试");
        // content 池无删除标记, 不同步摘除的话池分发与创作中心会把已删视频查回来
        contentService.removeById(videoId);
        // ES 尽力删除, 失败不影响 DB 删除, 列表查询以 DB is_delete 为准
        try {
            contentDocPort.deleteDoc(videoId);
        } catch (Exception e) {

            log.warn("ES 文档删除失败, videoId={}", videoId, e);
        }
    }

    @Override
    public void privateVideo(Long videoId) {
        Long userId = getUserId();
        OwnershipUpdate.updateOrFail(videoMapper, Video::getId, videoId, Video::getAuthorId, userId,
                w -> w.set(Video::getVisibility, VideoVisibilityEnum.PRIVATE), "修改失败");
        // 在es中更新这个文档
        contentDocPort.syncVisibility(videoId, new VisibilitySync(null, null, null, "private", false));
    }

    @Override
    public String publicVideo(Long videoId) {
        Long userId = getUserId();
        Video video = videoMapper.selectOne(new LambdaQueryWrapper<Video>()
                .eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId));
        if (video == null) {

            throw new BizException(BizException.BIZ_ERROR, "该视频不存在");
        }
        if (video.getStatus().equals(BANNED)) {

            throw new BizException(BizException.BIZ_ERROR, "该视频已被封禁,无法修改");
        }
        if (video.getStatus().equals(REVIEW)) {

            OwnershipUpdate.updateOrFail(videoMapper, Video::getId, videoId, Video::getAuthorId, userId,
                    w -> w.set(Video::getVisibility, VideoVisibilityEnum.PUBLIC), "修改失败");
            // 在es中更新这个文档
            contentDocPort.syncVisibility(videoId, new VisibilitySync(null, null, null, "public", false));
            return "修改成功,等待审核中";
        }
        // 在es中更新这个文档
        contentDocPort.syncVisibility(videoId, new VisibilitySync(video.getTitle(), video.getDescription(), video.getTags(), "public", true));
        return "修改成功";
    }

    @Override
    public void openComment(Long videoId) {
        Long userId = getUserId();
        OwnershipUpdate.updateOrFail(videoMapper, Video::getId, videoId, Video::getAuthorId, userId,
                w -> w.set(Video::getAllowComment, 1), "修改失败");
    }

    @Override
    public void closeComment(Long videoId) {
        Long userId = getUserId();
        OwnershipUpdate.updateOrFail(videoMapper, Video::getId, videoId, Video::getAuthorId, userId,
                w -> w.set(Video::getAllowComment, 0), "修改失败");
    }

    private static Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
