package cn.diaojiahong.myyoutube.admin.service.impl;

import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.BANNED;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.PUBLISHED;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements IAdminService {

    private final IVideoService videoService;
    private final IUserService userService;
    private final VideoDocRepository videoDocRepository;

    @Transactional
    @Override
    public Result<String> pass(Long videoId) {
        boolean success = videoService.lambdaUpdate()
                .set(Video::getStatus, PUBLISHED.getCode())
                .eq(Video::getId, videoId)
                .update();
        if (!success) {
            return Result.fail("发布失败");
        }
        // 发布成功, 作者作品数量加一
        Video video = videoService.lambdaQuery().eq(Video::getId, videoId).one();
        userService.lambdaUpdate()
                .eq(User::getId, video.getAuthorId())
                .setIncrBy(User::getVideoCount, 1)
                .update();
        // 修改es中的视频状态
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            doc.setStatus(PUBLISHED.getCode());
            videoDocRepository.save(doc);
        });
        return Result.success("发布成功");
    }

    @Override
    public Result<String> ban(Long videoId) {
        Video video = videoService.lambdaQuery()
                .eq(Video::getId, videoId)
                .one();
        if (video == null) {
            return Result.fail("视频不存在");
        }
        video.setStatus(BANNED);
        videoService.updateById(video);
        // 禁用成功, 作者作品数量减一
        userService.lambdaUpdate()
                .eq(User::getId, video.getAuthorId())
                .setDecrBy(User::getVideoCount, 1)
                .update();
        // 修改es中的视频状态
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            doc.setStatus(BANNED.getCode());
            videoDocRepository.save(doc);
        });
        return Result.success("禁用成功");
    }
}
