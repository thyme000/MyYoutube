package cn.diaojiahong.myyoutube.transcode.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HlsProbeTest {
    private static final String VIDEO = """
            {"codec_type":"video","codec_name":"h264","codec_tag_string":"avc1",
             "width":1280,"height":720,"avg_frame_rate":"30000/1001",
             "extradata":"\\n00000000: 0142 e01e ffe1 0018                       .B......\\n"}
            """;
    private static final String AUDIO = """
            {"codec_type":"audio","codec_name":"aac","profile":"LC"}
            """;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void codecsUseOutputAvcConfigurationAndOnlyAdvertiseExistingAudio(boolean audio) {
        FFmpegUtil.HlsProbe probe = FFmpegUtil.parseHlsProbe("{\"streams\":[" + VIDEO + (audio ? "," + AUDIO : "") + "]}");
        assertEquals("avc1.42e01e" + (audio ? ",mp4a.40.2" : ""), probe.codecs());
        assertEquals(30000.0 / 1001, probe.frameRate(), 0.000001);
        assertEquals(1280, probe.width());
        assertEquals(720, probe.height());
    }

    @Test
    void incompatibleOutputIsRejectedInsteadOfPublishingFalseCodecInformation() {
        assertThrows(IllegalStateException.class, () -> FFmpegUtil.parseHlsProbe(
                "{\"streams\":[" + VIDEO.replace("0142 e01e", "0242 e01e") + "]}"));
        assertThrows(IllegalStateException.class, () -> FFmpegUtil.parseHlsProbe(
                "{\"streams\":[" + VIDEO + "," + AUDIO.replace("LC", "HE-AAC") + "]}"));
    }
}
