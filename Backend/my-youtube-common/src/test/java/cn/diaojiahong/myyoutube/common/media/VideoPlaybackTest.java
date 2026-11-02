package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.ItemRole;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.Item;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.Manifest;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VideoPlaybackTest {
    @Test
    void pairsRenditionsByQualityAndPreservesBandwidth() {
        Manifest manifest = new Manifest(101L, 1L, 0, 9L, "a".repeat(64), List.of(
                new Item(ItemRole.VIDEO, 0, "source", "source", 0, 1920, 1080, 0, null),
                new Item(ItemRole.HLS_MEDIA, 720, "source", "720.mp4", 0, 1280, 720, 3000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 3000000, 25)),
                new Item(ItemRole.HLS_PLAYLIST, 720, "source", "720.m3u8", 0, 1280, 720, 3000000, null),
                new Item(ItemRole.HLS_PLAYLIST, 480, "source", "480.m3u8", 0, 854, 480, 1000000, null),
                new Item(ItemRole.HLS_MEDIA, 480, "source", "480.mp4", 0, 854, 480, 1000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 1000000, 25)),
                new Item(ItemRole.HLS_MEDIA, 0, "source", "source.mp4", 0, 1920, 1080, 5000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 5000000, 25)),
                new Item(ItemRole.HLS_PLAYLIST, 0, "source", "source.m3u8", 0, 0, 0, 0, null)), 1000, false, 0, null);

        VideoPlayback playback = VideoPlayback.from(manifest);
        assertEquals(1920, playback.width());
        assertEquals(1080, playback.height());
        assertEquals(List.of(480, 720, 0), playback.renditions().stream().map(VideoPlayback.Rendition::index).toList());
        assertEquals("480.m3u8", playback.renditions().get(0).playlistObjectKey());
        assertEquals("720.mp4", playback.renditions().get(1).mediaObjectKey());
        assertEquals("source.m3u8", playback.renditions().get(2).playlistObjectKey());
        assertEquals(5000000, playback.renditions().get(2).bandwidth());
        assertEquals(new PlaybackMetadata("avc1.64001f,mp4a.40.2", 5000000, 25), playback.renditions().get(2).playback());
        assertEquals(playback, JsonUtil.fromJson(JsonUtil.toJson(playback), VideoPlayback.class));
    }
}
