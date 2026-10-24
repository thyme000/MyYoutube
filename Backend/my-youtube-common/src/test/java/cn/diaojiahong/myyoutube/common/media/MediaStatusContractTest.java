package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MediaStatusContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(MediaTaskStatus.class)
    void preparationStatusKeepsItsWireValue(MediaTaskStatus status) throws Exception {
        var view = new PreparationContracts.View(1L, status, 2, 1, null, null, false);
        String jackson = json.writeValueAsString(view);
        String gson = JsonUtil.toJson(view);
        assertEquals(status.getCode(), json.readTree(jackson).get("status").asText());
        assertEquals(status.getCode(), json.readTree(gson).get("status").asText());
        assertEquals(status, json.readValue(gson, PreparationContracts.View.class).status());
        assertEquals(status, JsonUtil.fromJson(jackson, PreparationContracts.View.class).status());
    }

    @ParameterizedTest
    @EnumSource(PipelineStatus.class)
    void manifestStatusKeepsItsWireValue(PipelineStatus status) throws Exception {
        var manifest = new PipelineContracts.Manifest(1L, 2L, status, null, Map.of(), 3L);
        String jackson = json.writeValueAsString(manifest);
        String gson = JsonUtil.toJson(manifest);
        assertEquals(status.getCode(), json.readTree(jackson).get("status").asText());
        assertEquals(status.getCode(), json.readTree(gson).get("status").asText());
        assertEquals(status, json.readValue(gson, PipelineContracts.Manifest.class).status());
        assertEquals(status, JsonUtil.fromJson(jackson, PipelineContracts.Manifest.class).status());
    }
}
