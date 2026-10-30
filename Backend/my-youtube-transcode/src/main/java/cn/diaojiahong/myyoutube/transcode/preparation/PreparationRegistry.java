package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PreparationRegistry {
    private final MediaPreparationMapper preparations;
    private final PreparationItemMapper items;
    private final MediaTaskMapper taskRows;
    private final MediaTaskService tasks;
    private final TransactionTemplate transaction;
    private final MediaPreparationProperties properties;
    private final FrameProcessingSettings frameSettings;

    public void start(Request request) {
        validate(request);
        try { transaction.executeWithoutResult(tx -> {
            MediaPreparation existing = preparations.selectById(request.preparationId());
            if (existing != null) {
                if (!"ACTIVE".equals(existing.getLifecycle())) throw new IllegalStateException("Preparation is retired");
                if (!request.equals(JsonUtil.fromJson(existing.getRequestJson(), Request.class)))
                    throw new IllegalStateException("Preparation input mismatch");
                return;
            }
            preparations.insert(new MediaPreparation().setId(request.preparationId()).setDraftId(request.draftId())
                    .setMediaVersion(request.mediaVersion()).setUserId(request.userId())
                    .setRequestJson(JsonUtil.toJson(request)).setParameterVersion(parameterVersion()));
            tasks.startPreparation(
                    request.preparationId(),
                    request.userId(),
                    request.media(),
                    request.coverObjectKey());
        }); } catch (org.springframework.dao.DuplicateKeyException e) {
            MediaPreparation existing = preparations.selectById(request.preparationId());
            if (existing == null
                    || !request.equals(JsonUtil.fromJson(existing.getRequestJson(), Request.class))) throw e;
        }
    }

    private static void validate(Request request) {
        if (request == null || request.preparationId() == null || request.preparationId() <= 0
                || request.draftId() == null
                || request.draftId() <= 0
                || request.userId() == null
                || request.userId() <= 0
                || request.mediaVersion() < 0 || request.media() == null)
            throw new IllegalArgumentException("Incomplete preparation input");
        if (request.media().video() != null) MediaInputs.objectKey(request.media().video().objectKey());
        else MediaInputs.validate(request.media(), request.coverObjectKey());
    }

    public MediaPreparation get(Long id) {
        return Objects.requireNonNull(preparations.selectById(id), "Missing preparation");
    }

    public String parameterVersion() {
        return parameterVersion(frameSettings.snapshot());
    }

    public String parameterVersion(FrameProcessingSettings.Snapshot settings) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("media-preparation-v4:" + JsonUtil.toJson(List.of(settings.intervalSeconds(), settings.maxFrames(), settings.minSuccessPercent(),
                            properties.getVideoBrands().stream().sorted().toList(),
                            properties.getVideoProfiles().stream().sorted().toList(), properties.getVideoMaxLevel(),
                            properties.getLongEdge(), properties.getOutputPixels(), properties.getJpegQuality(),
                            properties.getSourceBytes(), properties.getVideoSourceBytes(), properties.getSourcePixels(),
                            properties.getSourceLongEdge()))).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public View view(Long id) {
        MediaPreparation preparation = get(id);
        MediaTask row = taskRows.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getOwnerId, id)
                .eq(MediaTask::getPhase, "DRAFT").eq(MediaTask::getTaskType, "PREPARE"));
        if (row == null) throw new IllegalStateException("Missing preparation task");
        boolean parametersChanged = !Objects.equals(preparation.getParameterVersion(), parameterVersion());
        String error = MediaTaskStatus.DEAD.equals(row.getStatus()) && parametersChanged
                ? PreparationContracts.PARAMETERS_CHANGED_MESSAGE : row.getErrorMessage();
        return new View(id, row.getStatus(), row.getAttempt(), row.getRetryCount(), error,
                MediaTaskStatus.SUCCESS.equals(row.getStatus()) ? JsonUtil.fromJson(row.getResultJson(), Manifest.class) : null,
                parametersChanged);
    }

    public PreparationItem find(Long id, String role, int index) {
        return items.selectOne(new LambdaQueryWrapper<PreparationItem>().eq(PreparationItem::getPreparationId, id)
                .eq(PreparationItem::getRole, role).eq(PreparationItem::getItemIndex, index));
    }

    public PreparationItem reserve(MediaTaskMessage task, String role, int index, String sourceJson) {
        return transaction.execute(tx -> {
            fence(task);
            PreparationItem current = find(task.getOwnerId(), role, index);
            if (current != null) return current;
            PreparationItem row = new PreparationItem()
                    .setPreparationId(task.getOwnerId())
                    .setRole(role)
                    .setItemIndex(index)
                    .setObjectOwnerId(task.getOwnerId())
                    .setObjectKey("media-prepared/" + task.getUserId() + "/" + task.getOwnerId() + "/"
                            + role.toLowerCase(Locale.ROOT) + "-" + index + "-" + UUID.randomUUID())
                    .setSourceJson(sourceJson).setStatus("ALLOCATED");
            items.insert(row);
            return row;
        });
    }

    public List<PreparationItem> reusableImages(MediaTaskMessage task, PreparationStorage.Source source) {
        MediaPreparation job = get(task.getOwnerId());
        return items.reusableImages(
                job.getDraftId(),
                job.getUserId(),
                job.getParameterVersion(),
                job.getId(),
                JsonUtil.toJson(source));
    }

    public boolean shareImages(MediaTaskMessage task, int index, PreparationItem original, PreparationItem derived) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            // Acquire physical owner and consumer in ID order, before taking the task fence.
            for (Long id : new TreeSet<>(List.of(original.getPreparationId(), task.getOwnerId())))
                preparations.selectOne(new LambdaQueryWrapper<MediaPreparation>().eq(MediaPreparation::getId, id).last("FOR UPDATE"));
            fence(task);
            MediaPreparation owner = get(original.getPreparationId());
            MediaPreparation consumer = get(task.getOwnerId());
            if (!Objects.equals(owner.getDraftId(), consumer.getDraftId())
                    || !Objects.equals(owner.getUserId(), consumer.getUserId())
                    || !owner.getParameterVersion().equals(consumer.getParameterVersion())) return false;
            PreparationItem source = items.selectById(original.getId());
            PreparationItem output = items.selectById(derived.getId());
            if (source == null
                    || output == null
                    || !"READY".equals(source.getStatus())
                    || !"READY".equals(output.getStatus())
                    || !Objects.equals(source.getObjectOwnerId(), owner.getId())
                    || !Objects.equals(output.getObjectOwnerId(), owner.getId())
                    || !Objects.equals(output.getPreparationId(), owner.getId()) || !"IMAGE".equals(source.getRole())
                    || !"OCR_IMAGE".equals(output.getRole())
                    || !source.getItemIndex().equals(output.getItemIndex())) return false;
            if (find(task.getOwnerId(), "IMAGE", index) != null
                    || find(task.getOwnerId(), "OCR_IMAGE", index) != null) return false;
            for (PreparationItem row : List.of(source, output)) items.insert(new PreparationItem().setPreparationId(task.getOwnerId())
                    .setObjectOwnerId(owner.getId())
                    .setRole(row.getRole())
                    .setItemIndex(index)
                    .setObjectKey(row.getObjectKey())
                    .setSourceJson(row.getSourceJson()).setResultJson(row.getResultJson()).setStatus("READY"));
            return true;
        }));
    }

    public PreparationItem planned(MediaTaskMessage task, PreparationItem row, String resultJson) {
        return transaction.execute(tx -> {
            fence(task);
            PreparationItem current = Objects.requireNonNull(items.selectById(row.getId()));
            if (!Objects.equals(current.getObjectOwnerId(), task.getOwnerId()))
                throw new IllegalStateException("Shared preparation objects are immutable");
            if (current.getResultJson() == null) current.setResultJson(resultJson);
            current.setStatus("PLANNED");
            items.updateById(current);
            return current;
        });
    }

    public void confirmed(MediaTaskMessage task, PreparationItem row) {
        transaction.executeWithoutResult(tx -> {
            fence(task);
            PreparationItem current = Objects.requireNonNull(items.selectById(row.getId()));
            if (!Objects.equals(current.getObjectOwnerId(), task.getOwnerId()))
                throw new IllegalStateException("Shared preparation objects are immutable");
            if (current.getResultJson() == null) throw new IllegalStateException("Missing planned object metadata");
            current.setStatus("READY");
            items.updateById(current);
        });
    }

    private void fence(MediaTaskMessage task) {
        MediaPreparation preparation = preparations.selectOne(new LambdaQueryWrapper<MediaPreparation>()
                .eq(MediaPreparation::getId, task.getOwnerId()).last("FOR UPDATE"));
        if (preparation == null || !"ACTIVE".equals(preparation.getLifecycle()))
            throw new IllegalStateException("Preparation is retired or missing");
        MediaTask row = taskRows.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, task.getTaskId()).last("FOR UPDATE"));
        if (row == null || !"DRAFT".equals(row.getPhase()) || !"PREPARE".equals(row.getTaskType())
                || !Objects.equals(row.getOwnerId(), task.getOwnerId())
                || !MediaTaskStatus.RUNNING.equals(row.getStatus())
                || !Objects.equals(row.getAttempt(), task.getAttempt())) throw new IllegalStateException("Preparation lease lost");
    }
}
