package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoDetailVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoVoConverter;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.PUBLISHED;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum.LONG_VIDEO;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum.SHORT_VIDEO;

/**
 * 视频信息表 服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements IVideoService {

    private final IUserService userService;

    private static final int SHORT_VIDEO_DURATION = 300;

    @Override
    public Result<String> publishVideo(VideoDTO videoDTO) {
        if (StrUtils.isEmpty(videoDTO.getVideoUrl())) {
            return Result.fail("视频地址不能为空～");
        }

        UserDTO user = UserContext.getUser();
        if (user == null) {
            return Result.fail("用户未登录,请先登录～");
        }

        Video video = BeanUtil.toBean(videoDTO, Video.class);

        video.setAuthorId(user.getId());

        if (video.getDuration() == null) {
            return Result.fail("视频时长不能为空～");
        }
        if (video.getDuration() <= SHORT_VIDEO_DURATION) {
            video.setVideoType(SHORT_VIDEO.getCode());
        } else {
            video.setVideoType(LONG_VIDEO.getCode());
        }

        save(video);

        return Result.success("视频发布成功,等待审核中...");
    }

    @Override
    public Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery) {
        Page<Video> page = pageQuery.toPage();
        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getIsDelete, 0);

        if (pageQuery.getCategory() != null) {
            q.eq(Video::getCategory, pageQuery.getCategory());
        }
        if (pageQuery.getUserId() != null) {
            q.eq(Video::getAuthorId, pageQuery.getUserId());
        }
        if (pageQuery.getBegin() != null) {
            q.ge(Video::getCreateTime, pageQuery.getBegin());
        }
        if (pageQuery.getEnd() != null) {
            q.le(Video::getCreateTime, pageQuery.getEnd());
        }
        if (pageQuery.getTags() != null && !pageQuery.getTags().isBlank()) {
            q.like(Video::getTags, pageQuery.getTags().trim());
        }

        applySort(q, pageQuery.getSort());
        Page<Video> result = page(page, q.eq(Video::getStatus, PUBLISHED));
        List<VideoListVO> list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                .collect(Collectors.toList());
        return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
    }

    @Override
    public Result<VideoDetailVO> getVideoDetail(Long videoId) {
        Video video = getById(videoId);

        if (video == null ) {
            return Result.fail("视频不存在或未发布～");
        }
        if (!PUBLISHED.getCode().equals(video.getStatus().getCode())) {
            return Result.fail("视频已私密或已被封禁～");
        }

        VideoDetailVO detailVo = VideoVoConverter.toDetailVo(video);

        // 获取作者的信息
        User author = userService.getById(video.getAuthorId());
        detailVo.setAuthorFanCount(author.getFans());
        detailVo.setAuthorAvatarUrl(author.getAvatarUrl());
        detailVo.setAuthorNickname(author.getNickname());

        return Result.success(detailVo);
    }

    @Override
    public Result<PageResult<VideoListVO>> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery) {
        Page<Video> page = pageQuery.toPage();
        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getIsDelete, 0);
        q.eq(Video::getAuthorId, authorId);
        q.eq(Video::getStatus, PUBLISHED);
        q.orderByDesc(Video::getCreateTime);
        // 判断是查询视频的类型
        List<VideoListVO> list;
        Page<Video> result;
        if (pageQuery.getVideoType().getCode().equals(LONG_VIDEO.getCode())) {
            // 查询所有
            result = page(page, q);
            list = result.getRecords().stream()
                    .map(VideoVoConverter::toListVo)
                    .toList();
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        } else if (pageQuery.getVideoType().getCode().equals(SHORT_VIDEO.getCode())) {
            // 查询短视频
            result = page(page, q.eq(Video::getVideoType, SHORT_VIDEO.getCode()));
            list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                    .toList();
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        } else {
            // 查询长视频
            result = page(page, q.eq(Video::getVideoType, LONG_VIDEO.getCode()));
            list = result.getRecords().stream()
                    .map(VideoVoConverter::toListVo)
                    .toList();
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        }
    }

    private static void applySort(LambdaQueryWrapper<Video> q, VideoSortEnum sort) {
        if (sort == null) {
            q.orderByDesc(Video::getCreateTime);
            return;
        }
        switch (sort) {
            case LATEST -> q.orderByDesc(Video::getCreateTime);
            case MOST_LIKED -> q.orderByDesc(Video::getLikeCount);
            case MOST_SHARED -> q.orderByDesc(Video::getShareCount);
            case MOST_PLAYED -> q.orderByDesc(Video::getPlayCount);
            default -> q.orderByDesc(Video::getCreateTime);
        }
    }
}
