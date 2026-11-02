package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** HLS 文件约定与主播放列表格式；地址由调用方按授权场景提供。 */
public final class HlsPlaylist {
    public static final String CONTENT_TYPE = "application/vnd.apple.mpegurl";
    public static final String MEDIA_CONTENT_TYPE = "video/mp4";
    public static final String MASTER_FILENAME = "master.m3u8";
    public static final String PLAYLIST_FILENAME = "index.m3u8";
    public static final String MEDIA_FILENAME = "media.mp4";
    public static final String DURATION_PREFIX = "#EXTINF:";
    public static final String BYTE_RANGE_PREFIX = "#EXT-X-BYTERANGE:";

    private static final String MASTER_HEADER = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-INDEPENDENT-SEGMENTS
            """;
    private static final String VARIANT_FORMAT = """
            #EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,CODECS="%s",FRAME-RATE=%.3f,RESOLUTION=%dx%d
            %s
            """;

    private HlsPlaylist() {
    }

    public static String master(List<VideoPlayback.Rendition> renditions,
                                Function<VideoPlayback.Rendition, String> playlistUrl) {
        var text = new StringBuilder(MASTER_HEADER);
        for (VideoPlayback.Rendition rendition : renditions) {
            PlaybackMetadata metadata = rendition.playback();
            text.append(String.format(Locale.ROOT, VARIANT_FORMAT,
                    rendition.bandwidth(), metadata.averageBandwidth(), metadata.codecs(), metadata.frameRate(),
                    rendition.width(), rendition.height(), playlistUrl.apply(rendition)));
        }
        return text.toString();
    }
}
