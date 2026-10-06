package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.dto.GenerateCoverDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoPublishService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.REVIEW;

/**
 * 视频发布: fail-fast 校验 + 事务消息(half)保证入库与封面任务原子 + 封面任务补发
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoPublishServiceImpl implements IVideoPublishService {

    private final RocketMQTemplate rocketMQTemplate;
    private final S3StorageUtil storageUtil;
    private final ContentMapper contentMapper;
    private final VideoMapper videoMapper;

    @Transactional
    @Override
    public String publishVideo(VideoDTO videoDTO) {

        // fail-fast: 校验全部前置, 通过后才进入半消息/本地事务
        if (StrUtils.isEmpty(videoDTO.getVideoUrl())) {

            throw new BizException(BizException.BIZ_ERROR, "视频地址不能为空～");
        }
        if (videoDTO.getDuration() == null) {

            throw new BizException(BizException.BIZ_ERROR, "视频时长不能为空～");
        }
        if (StrUtils.isEmpty(videoDTO.getTitle())) {

            throw new BizException(BizException.BIZ_ERROR, "视频标题不能为空～");
        }

        Long userId = getUserId();

        Video video = BeanUtil.toBean(videoDTO, Video.class);
        video.setVideoUrl(storageUtil.normalizeToObjectKey(video.getVideoUrl()));

        video.setAuthorId(userId);
        // 上传即公开, 不接受客户端指定可见性; 私密走发布后的设为私密入口
        video.setVisibility(VideoVisibilityEnum.PUBLIC);
        video.setCreateTime(LocalDateTime.now());
        video.setStatus(REVIEW);
        // 预分配雪花id, content 池与领域表共用; 事务消息回查时按它判断入库结果
        video.setId(IdWorker.getId());

        // 事务消息: half 消息先发出, 本地事务入库成功才提交, 保证"视频入库"与"封面任务"原子性
        VideoCoverMessage coverMsg = VideoCoverMessage.builder()
                .videoId(video.getId())
                .userId(userId)
                .videoObjectKey(videoDTO.getVideoUrl())
                .build();
        Message<VideoCoverMessage> message = MessageBuilder.withPayload(coverMsg)
                .setHeader("videoId", video.getId().toString())
                .build();
        rocketMQTemplate.sendMessageInTransaction(MqConstants.TOPIC_VIDEO_COVER, message, video);

        return video.getId() + "/" + "视频发布成功,等待审核中....";
    }

    @Transactional
    @Override
    public void persistNewVideo(Video video) {

        // content 池先行: 同一雪花id下先有"内容"再有领域记录
        Content content = new Content()
                .setId(video.getId())
                .setType(ContentTypeEnum.VIDEO)
                .setAuthorId(video.getAuthorId())
                .setStatus(video.getStatus() != null ? video.getStatus().getCode() : null)
                .setCreateTime(video.getCreateTime());
        contentMapper.insert(content);
        videoMapper.insert(video);
    }

    @Override
    public String generateVideoCover(GenerateCoverDTO generateCoverDTO) {
        // userId 从登录态取, 不信客户端入参
        VideoCoverMessage coverMsg = VideoCoverMessage.builder()
                .videoId(generateCoverDTO.getVideoId())
                .userId(getUserId())
                .videoObjectKey(generateCoverDTO.getVideoObjectKey())
                .build();
        rocketMQTemplate.syncSend(MqConstants.TOPIC_VIDEO_COVER, coverMsg);
        log.info("发送视频封面消息, 视频id: {}", coverMsg.getVideoId());
        return "发布成功";
    }

    private static Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
