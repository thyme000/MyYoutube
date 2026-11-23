package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.content.domain.po.MediaDraft;
import java.util.*;

final class PreparedDraftTestSupport {
    static Request request(MediaDraft draft) {
        ContentMedia media = draft.getType() == ContentTypeEnum.VIDEO
                ? new ContentMedia("video", new VideoMedia(draft.getObjectKey(), 0), null)
                : new ContentMedia("dynamic", null, JsonUtil.fromJson(draft.getMediaJson(), DynamicMedia.class));
        return new Request(
                draft.getPreparationId(),
                draft.getId(),
                draft.getMediaVersion(),
                draft.getUserId(),
                media,
                draft.getCoverObjectKey());
    }

    static Manifest manifest(Request request) {
        String prefix = "media-prepared/" + request.userId() + "/" + request.preparationId() + "/";
        var items = new ArrayList<Item>();
        if (request.media().video() != null) {
            String original = prefix + "video";
            items.add(new Item(ItemRole.VIDEO, 0, original, original, 0, 1280, 720, 0, null));
            items.add(new Item(ItemRole.HLS_MEDIA, 480, original, prefix + "playback-480.mp4", 0, 854, 480, 1000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 1000000, 25)));
            items.add(new Item(ItemRole.HLS_PLAYLIST, 480, original, prefix + "index-480.m3u8", 0, 854, 480, 0, null));
            items.add(new Item(ItemRole.HLS_MEDIA, 0, original, prefix + "playback-source.mp4", 0, 1280, 720, 2000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 2000000, 25)));
            items.add(new Item(ItemRole.HLS_PLAYLIST, 0, original, prefix + "index-source.m3u8", 0, 0, 0, 0, null));
            for (int i = 0; i < 8; i++) {
                items.add(new Item(ItemRole.FRAME, i, original, prefix + "frame-" + i, i * 100L, 80, 60, 0, null));
            }
        } else {
            DynamicMedia media = request.media().dynamic();
            for (int i = 0; i < media.items().size(); i++) {
                String original = prefix + "image-" + i;
                long time = media.items().get(i).startMs();
                items.add(new Item(ItemRole.IMAGE, i, original, original, time, 0, 0, 0, null));
                items.add(new Item(ItemRole.OCR_IMAGE, i, original, prefix + "ocr-" + i, time, 80, 60, 0, null));
            }
            if (media.audio() != null) items.add(new Item(ItemRole.AUDIO, 0, prefix + "audio", prefix + "audio", 0, 0, 0, 0, null));
        }
        return new Manifest(request.preparationId(), request.draftId(), request.mediaVersion(), request.userId(), "a"
                .repeat(64), items,
                request.media().video() != null ? 1501 : request.media().durationMs(), request.media().dynamic() != null
                        && request.media().dynamic().audio() != null,
                request.media().video() != null ? 8 : 0,
                request.media().video() != null ? VideoFrameSampling.plan(8, 80) : null);
    }

    static void ready(MediaDraft draft) {
        PreparationContracts.Request request = request(draft);
        PreparedMedia.Snapshot snapshot = PreparedMedia.freeze(request, manifest(request));
        draft.setPreparedJson(JsonUtil.toJson(snapshot.media())).setPreparedCoverObjectKey(snapshot.coverObjectKey());
    }
}
