package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补发已完成直传但丢失的上传事件。过期及产物回收由准备生命周期负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaDraftCleaner {

    /** UPLOADING 滞留超此值时 HeadObject 补桥(对象在=事件丢了, 对象不在=直传放弃) */
    private static final int UPLOADING_STALE_HOURS = 6;

    private final MediaDraftMapper draftMapper;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
    @ObservedBusiness(BusinessOperation.CLEAN_DRAFTS)
    public void clean() {
        LocalDateTime now = LocalDateTime.now();
        List<MediaDraft> stale = draftMapper.selectList(new LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING)
                .eq(MediaDraft::getType, ContentTypeEnum.VIDEO).gt(MediaDraft::getExpiresAt, now)
                .lt(MediaDraft::getUpdateTime, now.minusHours(UPLOADING_STALE_HOURS))
                .last("limit 200"));
        if (stale.isEmpty()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        for (MediaDraft draft : stale) {
            try {
                repairUpload(draft);
            } catch (Exception e) {
                metrics.counter(MetricNames.MAINTENANCE_DEFERRED, "stage", "draft_cleanup").increment();
                log.warn("草稿治理失败: draftId={}", draft.getId(), e);
            }
        }
        log.info("Draft cleanup completed scanned={} elapsedMs={}", stale.size(), System.currentTimeMillis() - startedAt);
    }

    private void repairUpload(MediaDraft draft) {
        if (storageUtil.objectExists(draft.getObjectKey())) {
            log.warn("上传事件缺失, 已补发上传事件 draftId={}", draft.getId());
            rocketMQTemplate.syncSend(MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, MediaUploadEventMessage.builder()
                    .draftId(draft.getId()).userId(draft.getUserId()).objectKey(draft.getObjectKey()).build());
            metrics.counter(MetricNames.UPLOAD_EVENT_REPAIRS).increment();
        }
    }
}
