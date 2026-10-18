package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import java.util.*;

final class PreparedDraftTestSupport {
    static Request request(MediaDraft draft) {
        var media = draft.getType() == ContentTypeEnum.VIDEO
                ? new ContentMedia("video", new VideoMedia(draft.getObjectKey(), 0), null)
                : new ContentMedia("dynamic", null, JsonUtil.fromJson(draft.getMediaJson(), DynamicMedia.class));
        return new Request(draft.getPreparationId(), draft.getId(), draft.getMediaVersion(), draft.getUserId(), media, draft.getCoverObjectKey());
    }
    static Manifest manifest(Request request) {
        String prefix = "media-prepared/" + request.userId() + "/" + request.preparationId() + "/";
        var items = new ArrayList<Item>();
        if (request.media().video() != null) {
            String original = prefix + "video";
            items.add(new Item("VIDEO", 0, original, original, 0, 0, 0));
            for (int i = 0; i < 8; i++) items.add(new Item("FRAME", i, original, prefix + "frame-" + i, i * 100L, 80, 60));
        } else {
            var media = request.media().dynamic();
            for (int i = 0; i < media.items().size(); i++) {
                String original = prefix + "image-" + i;
                long time = media.items().get(i).startMs();
                items.add(new Item("IMAGE", i, original, original, time, 0, 0));
                items.add(new Item("OCR_IMAGE", i, original, prefix + "ocr-" + i, time, 80, 60));
            }
            if (media.audio() != null) items.add(new Item("AUDIO", 0, prefix + "audio", prefix + "audio", 0, 0, 0));
        }
        return new Manifest(request.preparationId(), request.draftId(), request.mediaVersion(), request.userId(), "a".repeat(64), items,
                request.media().video() != null ? 1501 : request.media().durationMs(), request.media().dynamic() != null && request.media().dynamic().audio() != null,
                request.media().video() != null ? 8 : 0, request.media().video() != null ? VideoFrameSampling.plan(8, 80) : null);
    }
    static void ready(MediaDraft draft) {
        var request = request(draft); var snapshot = PreparedMedia.freeze(request, manifest(request));
        draft.setPreparedJson(JsonUtil.toJson(snapshot.media())).setPreparedCoverObjectKey(snapshot.coverObjectKey());
    }
}
