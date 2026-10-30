package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.media.DynamicTimeline;
import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.MediaInputs;
import cn.diaojiahong.myyoutube.video.chain.dynamic.*;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicDraftDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class DynamicMediaPreparationService {
    private final SyncChainStepRegistry chains;

    public record Prepared(DynamicMedia media, String coverObjectKey) {
    }

    public Prepared prepare(DynamicDraftDTO dto, Long userId) {
        if (dto == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "动态草稿不能为空");
        DynamicPreparationContext ctx = DynamicPreparationContext.of(dto, userId);
        chains.execute(DynamicPreparationChain.GROUP, ctx);
        DynamicTimeline.Schedule timing = DynamicTimeline.schedule(
                Arrays.stream(ctx.getDurations()).boxed().toList(),
                ctx.getFadeMs());
        if (timing.exportDurationMs() > Integer.MAX_VALUE)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "图片总时长超出范围");
        var items = new ArrayList<DynamicMedia.Image>();
        for (int i = 0; i < ctx.getImageKeys().size(); i++) {
            DynamicDraftDTO.DynamicItemDTO item = dto.getItems().get(i);
            items.add(new DynamicMedia.Image(ctx.getImageKeys().get(i), timing.startsMs().get(i),
                    ctx.getDurations()[i], DynamicPreparationRules.clampRatio(item.getFocusX()),
                    DynamicPreparationRules.clampRatio(item.getFocusY())));
        }
        String cover = dto.getCoverImageObjectKey() == null ? ctx.getImageKeys().get(0) : dto.getCoverImageObjectKey();
        if (!ctx.getImageKeys().contains(cover))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "封面必须属于当前动态图片");
        DynamicMedia.Audio audio = ctx.getAudioId() == null ? null : new DynamicMedia.Audio(ctx.getAudioId(),
                ctx.getAudioObjectKey(), ctx.getAudioTitle(), ctx.getAudioMs());
        var media = new DynamicMedia(ctx.getCanvasType(), ctx.getCanvasW(), ctx.getCanvasH(), ctx.getTransition(),
                ctx.getFadeMs(), items, audio, timing.exportDurationMs());
        MediaInputs.validate(new ContentMedia("dynamic", null, media), cover);
        return new Prepared(media, cover);
    }

    public void validate(DynamicMedia media, String cover, Long userId) {
        MediaInputs.validate(new ContentMedia("dynamic", null, media), cover);
        var dto = new DynamicDraftDTO();
        dto.setCanvasType(media.canvasType());
        dto.setTransition(media.transition());
        dto.setCoverImageObjectKey(cover);
        dto.setAudioId(media.audio() == null ? null : media.audio().id());
        dto.setItems(media.items().stream().map(image -> {
            var item = new DynamicDraftDTO.DynamicItemDTO();
            item.setImageObjectKey(image.imageObjectKey());
            item.setDurationMs(image.durationMs());
            item.setFocusX(image.focusX());
            item.setFocusY(image.focusY());
            return item;
        }).toList());
        if (!prepare(dto, userId).media().equals(media))
            throw new BizException(ErrorCode.STATE_CONFLICT, "素材已变化，请重新保存草稿");
    }
}
