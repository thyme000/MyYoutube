package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.mq.message.VideoDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.po.VideoTag;
import cn.diaojiahong.myyoutube.recommend.mapper.VideoTagMapper;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.recommend.service.IVideoTagService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.Arrays;

import java.util.List;
/**
 * <p>
 * 视频-标签关联表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
@RequiredArgsConstructor
public class VideoTagServiceImpl extends ServiceImpl<VideoTagMapper, VideoTag> implements IVideoTagService {

    private final ITagService tagService;
    private final RabbitTemplate rabbitTemplate;

    @Async
    @Override
    @Transactional
    public void saveTagsAsync(String tags, Long videoId, Video video) {
        if (videoId == null) {
            throw new IllegalArgumentException("视频id不能为空!");
        }

        // 删除重复的标签关联 (方便后面扩展编辑标签)
        lambdaUpdate().eq(VideoTag::getVideoId, videoId).remove();

        // 获取所有的标签id,没有的标签id,则创建新的标签
        List<Long> tagIds = tagService.getTagIdsBySlugs(tags);

        // 批量插入
        saveBatch(tagIds.stream().map(tagId -> new VideoTag().setVideoId(videoId).setTagId(tagId)).toList());

        // 发送消息给RabbitMQ,用于更新视频文档
        rabbitTemplate.convertAndSend(
                RabbitMqConstants.UPDATE_VIDEO_DOCUMENT_EXCHANGE,
                RabbitMqConstants.UPDATE_VIDEO_DOCUMENT_ROUTING_KEY,
                VideoDocMessage.builder().video(video).tagIds(tagIds).build());
    }
}
