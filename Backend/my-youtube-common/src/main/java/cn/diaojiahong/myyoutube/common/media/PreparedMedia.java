package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import java.util.*;

public final class PreparedMedia {
    private PreparedMedia() {}
    public record Snapshot(ContentMedia media, String coverObjectKey) {}

    public static Snapshot freeze(Request request, Manifest manifest) {
        if (manifest == null || !Objects.equals(request.preparationId(), manifest.preparationId())
                || !Objects.equals(request.draftId(), manifest.draftId()) || request.mediaVersion() != manifest.mediaVersion()
                || !Objects.equals(request.userId(), manifest.userId()) || manifest.durationMs() <= 0
                || manifest.parameterVersion() == null || !manifest.parameterVersion().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Preparation identity mismatch");
        var entries = new HashMap<String, Item>();
        var keys = new HashMap<String, Item>();
        for (var item : manifest.items()) {
            if (item == null || item.index() < 0 || item.timeMs() < 0) throw new IllegalArgumentException("Invalid prepared item");
            MediaInputs.objectKey(item.objectKey()); MediaInputs.objectKey(item.originalObjectKey());
            if (!item.objectKey().matches("media-prepared/" + request.userId() + "/[1-9][0-9]*/[^/]+")
                    || entries.put(item.role() + ":" + item.index(), item) != null)
                throw new IllegalArgumentException("Duplicate or unprotected prepared item");
            var previous = keys.putIfAbsent(item.objectKey(), item);
            if (previous != null && (!previous.role().equals(item.role())
                    || !previous.originalObjectKey().equals(item.originalObjectKey())
                    || previous.width() != item.width() || previous.height() != item.height()))
                throw new IllegalArgumentException("Conflicting shared object metadata");
        }
        ContentMedia frozen;
        String cover = null;
        if (request.media().video() != null) {
            var original = take(entries, "VIDEO", 0);
            original(original);
            for (int i = 0; i < 8; i++) {
                var frame = take(entries, "FRAME", i);
                derived(frame, original.objectKey());
                if (frame.timeMs() > manifest.durationMs()) throw new IllegalArgumentException("Frame time out of bounds");
            }
            frozen = new ContentMedia("video", new VideoMedia(original.objectKey(), manifest.durationMs()), null, manifest);
        } else {
            var dynamic = request.media().dynamic();
            MediaInputs.validate(new ContentMedia("dynamic", null, dynamic), request.coverObjectKey());
            if (dynamic.exportDurationMs() != manifest.durationMs() || (dynamic.audio() != null) != manifest.hasAudio())
                throw new IllegalArgumentException("Prepared dynamic timing mismatch");
            var images = new ArrayList<DynamicMedia.Image>();
            for (int i = 0; i < dynamic.items().size(); i++) {
                var input = dynamic.items().get(i);
                var original = take(entries, "IMAGE", i); original(original);
                var ocr = take(entries, "OCR_IMAGE", i); derived(ocr, original.objectKey());
                if (original.timeMs() != input.startMs() || ocr.timeMs() != input.startMs())
                    throw new IllegalArgumentException("Prepared image timing mismatch");
                images.add(new DynamicMedia.Image(original.objectKey(), input.startMs(), input.durationMs(), input.focusX(), input.focusY()));
                if (input.imageObjectKey().equals(request.coverObjectKey())) cover = original.objectKey();
            }
            DynamicMedia.Audio audio = null;
            if (dynamic.audio() != null) {
                var original = take(entries, "AUDIO", 0); original(original);
                var input = dynamic.audio();
                audio = new DynamicMedia.Audio(input.id(), original.objectKey(), input.title(), input.durationMs());
            }
            frozen = new ContentMedia("dynamic", null, new DynamicMedia(dynamic.canvasType(), dynamic.canvasW(), dynamic.canvasH(),
                    dynamic.transition(), dynamic.fadeMs(), images, audio, dynamic.exportDurationMs()), manifest);
        }
        if (!entries.isEmpty()) throw new IllegalArgumentException("Unexpected prepared items");
        MediaInputs.validate(new ContentMedia(frozen.type(), frozen.video(), frozen.dynamic()), cover);
        return new Snapshot(frozen, cover);
    }

    public static void validate(ContentMedia media, String cover) {
        var manifest = Objects.requireNonNull(media.preparation(), "Missing preparation manifest");
        var source = new ContentMedia(media.type(), media.video(), media.dynamic());
        var snapshot = freeze(new Request(manifest.preparationId(), manifest.draftId(), manifest.mediaVersion(), manifest.userId(), source, cover), manifest);
        if (!snapshot.media().equals(media)) throw new IllegalArgumentException("Media does not match preparation");
    }

    public static void validateOcr(ContentMedia media, ContentArtifacts.Ocr ocr) {
        String role = media.video() != null ? "FRAME" : "OCR_IMAGE";
        var inputs = media.preparation().items().stream().filter(item -> role.equals(item.role()))
                .sorted(Comparator.comparingInt(Item::index)).toList();
        if (ocr == null || ocr.frames() == null || ocr.frames().size() != inputs.size())
            throw new IllegalArgumentException("Incomplete prepared OCR frames");
        for (int i = 0; i < inputs.size(); i++) {
            var item = inputs.get(i); var frame = ocr.frames().get(i);
            String original = media.video() != null ? item.objectKey() : item.originalObjectKey();
            if (frame == null || frame.imageIndex() != item.index() || frame.timeMs() != item.timeMs()
                    || !original.equals(frame.objectKey()) || !item.objectKey().equals(frame.derivedObjectKey()))
                throw new IllegalArgumentException("OCR does not match preparation manifest");
        }
    }

    private static Item take(Map<String, Item> entries, String role, int index) {
        return Objects.requireNonNull(entries.remove(role + ":" + index), "Missing prepared " + role + ":" + index);
    }
    private static void original(Item item) {
        if (!item.objectKey().equals(item.originalObjectKey())) throw new IllegalArgumentException("Original mapping mismatch");
    }
    private static void derived(Item item, String original) {
        if (!item.originalObjectKey().equals(original) || item.width() <= 0 || item.height() <= 0)
            throw new IllegalArgumentException("Derived mapping mismatch");
    }
}
