package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.Item;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.Manifest;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.ItemRole;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 发布时保存的 HLS 播放资源；上传原件仍由 VideoMedia 保存，供下载和分析使用。 */
public record VideoPlayback(int width, int height, List<Rendition> renditions) {
    public VideoPlayback {
        renditions = List.copyOf(renditions);
    }

    public record Rendition(int index, String mediaObjectKey, String playlistObjectKey, int width, int height, long bandwidth, PlaybackMetadata playback) {}

    public static VideoPlayback from(Manifest manifest) {
        Objects.requireNonNull(manifest, "缺少准备产物");
        Item original = manifest.items().stream().filter(item -> ItemRole.VIDEO.equals(item.role()))
                .findFirst().orElseThrow();
        Map<Integer, String> playlists = manifest.items().stream()
                .filter(item -> ItemRole.HLS_PLAYLIST.equals(item.role()))
                .collect(Collectors.toMap(Item::index, Item::objectKey));
        List<Rendition> renditions = manifest.items().stream()
                .filter(item -> ItemRole.HLS_MEDIA.equals(item.role()))
                .sorted(Comparator.comparingInt(item -> Math.min(item.width(), item.height())))
                .map(item -> new Rendition(item.index(), item.objectKey(), playlists.get(item.index()), item.width(), item.height(), item.bandwidth(), item.playback()))
                .toList();
        return new VideoPlayback(original.width(), original.height(), renditions);
    }
}
