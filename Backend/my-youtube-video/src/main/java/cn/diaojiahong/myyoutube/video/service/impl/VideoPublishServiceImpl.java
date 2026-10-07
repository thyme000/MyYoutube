package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.support.ContentDocMessages;
import cn.diaojiahong.myyoutube.mq.publisher.AutoTagPublisher;
import cn.diaojiahong.myyoutube.recommend.auto.DraftTagMapper;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.dto.DraftPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.GenerateCoverDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.DraftStatusVO;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoPublishService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.REVIEW;

/**
 * 视频发布: fail-fast 校验 + 事务消息(half)保证入库与封面任务原子 + 封面任务补发
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoPublishServiceImpl implements IVideoPublishService {

    private final RocketMQTemplate rocketMQTemplate;
    private final AutoTagPublisher autoTagPublisher;
    private final S3StorageUtil storageUtil;
    private final ContentMapper contentMapper;
    private final VideoMapper videoMapper;
    private final MediaDraftService mediaDraftService;
    private final MediaTaskPort mediaTaskPort;
    private final DraftTagMapper draftTagMapper;
    private final IContentTagService contentTagService;
    private final TagPoolHolder tagPoolHolder;
    private final EsSyncPublisher esSyncPublisher;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String publishVideo(VideoDTO videoDTO) {

        // fail-fast: 校验全部前置, 通过后才进入半消息/本地事务
        // 本方法自身不写库也不能开事务: 监听器的 persistNewVideo 会以外层事务为准, 导致 COMMIT 消息发出时本地事务尚未提交
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
        // ES 全量文档由打标链路单写: 延迟任务里 LLM 打标后整档同步(失败也发兜底文档保可搜)
        autoTagPublisher.sendAutoTagTask(video.getId(), ContentTypeEnum.VIDEO);
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

    /**
     * 草稿发布: 管线产物(转码成片/封面/标签)直接转正, 秒级操作。
     * video/content 复用草稿雪花id, PK 冲突即重复发布, 天然幂等
     */
    @Override
    public String publishDraft(DraftPublishDTO dto) {
        Long userId = getUserId();
        if (StrUtils.isEmpty(dto.getTitle())) {
            throw new BizException(BizException.BIZ_ERROR, "视频标题不能为空～");
        }
        MediaDraft draft = mediaDraftService.getById(dto.getDraftId());
        if (draft == null || !userId.equals(draft.getUserId())) {
            throw new BizException(BizException.BIZ_ERROR, "草稿不存在～");
        }
        if (draft.getStatus() != MediaDraftStatusEnum.READY) {
            throw new BizException(BizException.BIZ_ERROR, "草稿未就绪, 当前状态: " + draft.getStatus().getDesc());
        }

        MediaTaskResult tasks = fetchTaskResults(draft.getId());

        // 标签映射(词库+防线+新词护栏), tag/content_tag 唯一写入方仍是 video 服务
        DraftTagMapper.Mapped mapped = tasks.tagJson != null
                ? draftTagMapper.map(tasks.tagJson)
                : new DraftTagMapper.Mapped(null, new java.util.LinkedHashMap<>(), List.of(), List.of());

        Video video = new Video()
                // 草稿id即内容id: 重复发布撞 PK, 幂等护栏
                .setId(draft.getId())
                .setTitle(dto.getTitle().trim())
                .setDescription(dto.getDescription())
                .setVideoUrl(tasks.processedKey != null ? tasks.processedKey : draft.getObjectKey())
                .setCoverUrl(tasks.coverKey)
                .setDuration(tasks.durationMs != null ? (int) (tasks.durationMs / 1000) : null)
                .setCategory(mapped.categoryCode() != null
                        ? cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum.fromCode(mapped.categoryCode())
                        : null)
                .setTags(String.join(",", mapped.names()))
                .setAuthorId(userId)
                .setVisibility(VideoVisibilityEnum.PUBLIC)
                .setStatus(REVIEW)
                .setCreateTime(LocalDateTime.now());

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                contentMapper.insert(new Content()
                        .setId(video.getId())
                        .setType(ContentTypeEnum.VIDEO)
                        .setAuthorId(video.getAuthorId())
                        .setStatus(video.getStatus().getCode())
                        .setCreateTime(video.getCreateTime()));
                videoMapper.insert(video);
                contentTagService.saveAutoTags(video.getId(), mapped.weights());
            });
        } catch (DuplicateKeyException e) {
            throw new BizException(BizException.BIZ_ERROR, "该草稿已发布～");
        }

        // 事务提交后: 引用计数(事件式守恒) + ES 全量文档(search 消费侧自动补向量) + 草稿转正
        for (cn.diaojiahong.myyoutube.recommend.domain.po.Tag tag : mapped.tags()) {
            tagPoolHolder.incrRefCount(tag, 1);
        }
        try {
            esSyncPublisher.syncContentDoc(ContentDocMessages.of(video,
                    List.copyOf(mapped.weights().keySet())));
        } catch (Exception e) {
            log.warn("草稿发布 ES 同步失败, 等夜巡兜底: contentId={}", video.getId(), e);
        }
        mediaDraftService.transition(draft.getId(), MediaDraftStatusEnum.READY,
                MediaDraftStatusEnum.PUBLISHED, null, null);
        return video.getId() + "/视频发布成功,等待审核中....";
    }

    /** 拉任务结果并解析; transcode 不可用时降级(原始文件直发, 无封面无标签) */
    private MediaTaskResult fetchTaskResults(Long draftId) {
        MediaTaskResult result = new MediaTaskResult();
        List<MediaTaskPort.TaskResult> tasks;
        try {
            tasks = mediaTaskPort.getTaskResults(draftId);
        } catch (Exception e) {
            log.warn("任务结果拉取失败, 降级发布: draftId={}", draftId, e);
            return result;
        }
        if (tasks == null) {
            return result;
        }
        for (MediaTaskPort.TaskResult t : tasks) {
            if (!"SUCCESS".equals(t.getStatus()) || t.getResultJson() == null) {
                continue;
            }
            try {
                JsonObject json = JsonUtil.GSON.fromJson(t.getResultJson(), JsonObject.class);
                switch (t.getTaskType()) {
                    case "TRANSCODE" -> {
                        if (json.has("processedObjectKey")) {
                            result.processedKey = json.get("processedObjectKey").getAsString();
                        }
                        if (json.has("durationMs")) {
                            result.durationMs = json.get("durationMs").getAsLong();
                        }
                    }
                    case "COVER" -> {
                        if (json.has("coverObjectKey")) {
                            result.coverKey = json.get("coverObjectKey").getAsString();
                        }
                    }
                    case "TAG" -> result.tagJson = t.getResultJson();
                    default -> {
                        // 未知类型忽略
                    }
                }
            } catch (Exception e) {
                log.warn("任务结果解析失败: draftId={} type={}", draftId, t.getTaskType(), e);
            }
        }
        return result;
    }

    @Override
    public DraftStatusVO getDraftStatus(Long draftId) {
        Long userId = getUserId();
        MediaDraft draft = mediaDraftService.getById(draftId);
        if (draft == null || !userId.equals(draft.getUserId())) {
            throw new BizException(BizException.BIZ_ERROR, "草稿不存在～");
        }
        DraftStatusVO vo = new DraftStatusVO();
        vo.setDraftId(draft.getId());
        vo.setStatus(draft.getStatus().getCode());
        vo.setErrorMessage(draft.getErrorMessage());
        try {
            List<MediaTaskPort.TaskResult> tasks = mediaTaskPort.getTaskResults(draftId);
            if (tasks != null) {
                vo.setTasks(tasks.stream().map(t -> {
                    DraftStatusVO.TaskProgress p = new DraftStatusVO.TaskProgress();
                    p.setTaskType(t.getTaskType());
                    p.setStatus(t.getStatus());
                    return p;
                }).toList());
            }
        } catch (Exception e) {
            log.warn("任务进度拉取失败: draftId={}", draftId, e);
        }
        return vo;
    }

    private static class MediaTaskResult {
        String processedKey;
        Long durationMs;
        String coverKey;
        String tagJson;
    }
}
