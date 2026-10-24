package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Set;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreparationReclaimer {
    private final MediaPreparationMapper preparations;
    private final PreparationItemMapper items;
    private final MediaTaskMapper tasks;
    private final PipelineMapper pipelines;
    private final PreparationLifecycle lifecycle;
    private final MediaDraftPort video;
    private final PreparationStorage storage;
    private final TransactionTemplate transaction;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private static final Set<MediaTaskStatus> TERMINAL = Set.of(MediaTaskStatus.SUCCESS, MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED);

    @Scheduled(fixedDelay = 900000, initialDelay = 300000)
    @ObservedBusiness(BusinessOperation.RECLAIM_MEDIA)
    public void reclaim() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long cursor = 0;
        while (true) {
            var page = preparations.selectList(new LambdaQueryWrapper<MediaPreparation>().gt(MediaPreparation::getId, cursor)
                    .le(MediaPreparation::getReclaimAfter, now).orderByAsc(MediaPreparation::getId).last("LIMIT 100"));
            if (page.isEmpty()) return;
            cursor = page.get(page.size() - 1).getId();
            for (var candidate : page) try { inspect(candidate, now); }
            catch (Exception e) {
                metrics.counter("myyoutube.maintenance.deferred", "stage", "preparation_reclaim").increment();
                log.warn("Preparation reclamation deferred preparationId={}", candidate.getId(), e);
            }
        }
    }

    private void inspect(MediaPreparation candidate, LocalDateTime now) {
        // Take a bounded delivery slot before network IO; a crash leaves a retryable record.
        if (preparations.update(null, new LambdaUpdateWrapper<MediaPreparation>().eq(MediaPreparation::getId, candidate.getId())
                .le(MediaPreparation::getReclaimAfter, now).set(MediaPreparation::getReclaimAfter, now.plusMinutes(15))) == 0) return;
        String reference = video.preparationReference(new PreparationReference(candidate.getId(), candidate.getDraftId(),
                candidate.getUserId(), candidate.getMediaVersion()));
        if (!"RELEASED".equals(reference)) return;
        boolean delete = Boolean.TRUE.equals(transaction.execute(tx -> {
            // Lock order matches task registration, retry and claim: Pipeline -> preparation -> tasks.
            pipelines.selectOne(new LambdaQueryWrapper<Pipeline>().eq(Pipeline::getContentId, candidate.getDraftId()).last("FOR UPDATE"));
            var row = lifecycle.lock(candidate.getId());
            var consumers = tasks.selectList(new LambdaQueryWrapper<MediaTask>().and(q -> q
                    .eq(MediaTask::getOwnerId, row.getId()).eq(MediaTask::getPhase, "DRAFT").eq(MediaTask::getTaskType, "PREPARE")
                    .or().eq(MediaTask::getOwnerId, row.getDraftId()).in(MediaTask::getPhase, "CONTENT", "REVIEW"))
                    .last("FOR UPDATE"));
            if (consumers.isEmpty() || consumers.stream().anyMatch(task -> !TERMINAL.contains(task.getStatus()))) return false;
            if ("ACTIVE".equals(row.getLifecycle())) {
                row.setLifecycle("RETIRED").setRetiredAt(now).setReclaimAfter(now.plusDays(1));
                preparations.updateById(row);
                return false;
            }
            return "RETIRED".equals(row.getLifecycle()) && row.getRetiredAt() != null
                    && !row.getRetiredAt().plusDays(1).isAfter(now);
        }));
        if (!delete) return;
        String prefix = "media-prepared/" + candidate.getUserId() + "/" + candidate.getId() + "/";
        for (var item : items.selectList(new LambdaQueryWrapper<PreparationItem>().eq(PreparationItem::getPreparationId, candidate.getId()))) {
            if (!Objects.equals(item.getObjectOwnerId(), candidate.getId())) {
                String ownerPrefix = "media-prepared/" + candidate.getUserId() + "/" + item.getObjectOwnerId() + "/";
                if (!item.getObjectKey().startsWith(ownerPrefix)) throw new IllegalStateException("Shared object ownership mismatch");
                items.update(null, new LambdaUpdateWrapper<PreparationItem>().eq(PreparationItem::getId, item.getId())
                        .set(PreparationItem::getStatus, "DELETED"));
                continue;
            }
            if (!item.getObjectKey().startsWith(prefix)) throw new IllegalStateException("Preparation object ownership mismatch");
            boolean unreferenced = Boolean.TRUE.equals(transaction.execute(tx -> {
                lifecycle.lock(candidate.getId());
                if (items.activeReferences(item.getObjectKey(), candidate.getId()) > 0) return false;
                // Sharing takes the same owner lock and only accepts READY objects.
                items.update(null, new LambdaUpdateWrapper<PreparationItem>().eq(PreparationItem::getId, item.getId())
                        .set(PreparationItem::getStatus, "DELETING"));
                return true;
            }));
            if (!unreferenced) continue;
            storage.delete(item.getObjectKey());
            items.update(null, new LambdaUpdateWrapper<PreparationItem>().eq(PreparationItem::getId, item.getId())
                    .set(PreparationItem::getStatus, "DELETED"));
        }
        // Keep tombstones and sweep again: an IO operation from a worker with a lost lease can finish late.
        preparations.update(null, new LambdaUpdateWrapper<MediaPreparation>().eq(MediaPreparation::getId, candidate.getId())
                .set(MediaPreparation::getReclaimAfter, now.plusDays(1)));
    }
}
