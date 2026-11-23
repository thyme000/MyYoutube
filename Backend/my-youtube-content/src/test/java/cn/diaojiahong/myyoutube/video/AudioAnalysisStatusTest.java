package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.domain.enums.AudioAnalysisStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class AudioAnalysisStatusTest {
    @ParameterizedTest @EnumSource(AudioAnalysisStatus.class)
    void lowercaseCodeRoundTripsThroughJackson(AudioAnalysisStatus status) throws Exception {
        var json = new ObjectMapper();
        assertEquals(status.name().toLowerCase(java.util.Locale.ROOT), status.getCode());
        assertEquals('"' + status.getCode() + '"', json.writeValueAsString(status));
        assertEquals(status, json.readValue('"' + status.getCode() + '"', AudioAnalysisStatus.class));
    }
}
