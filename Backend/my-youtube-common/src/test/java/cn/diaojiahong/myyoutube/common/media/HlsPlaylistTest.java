package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HlsPlaylistTest {
    @Test
    void masterPreservesProtocolTextAndUsesEachRenditionsAuthorizedUrl() {
        List<VideoPlayback.Rendition> renditions = List.of(
                new VideoPlayback.Rendition(480, "480.mp4", "480.m3u8", 854, 480, 1500000,
                        new PlaybackMetadata("avc1.64001f,mp4a.40.2", 1200000, 29.97)),
                new VideoPlayback.Rendition(0, "source.mp4", "source.m3u8", 1920, 1080, 5000000,
                        new PlaybackMetadata("avc1.640028", 4000000, 25)));

        String playlist = HlsPlaylist.master(renditions,
                rendition -> rendition.index() + "/index.m3u8?playbackToken=test");

        assertEquals("""
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-STREAM-INF:BANDWIDTH=1500000,AVERAGE-BANDWIDTH=1200000,CODECS="avc1.64001f,mp4a.40.2",FRAME-RATE=29.970,RESOLUTION=854x480
                480/index.m3u8?playbackToken=test
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=4000000,CODECS="avc1.640028",FRAME-RATE=25.000,RESOLUTION=1920x1080
                0/index.m3u8?playbackToken=test
                """, playlist);
    }
}
