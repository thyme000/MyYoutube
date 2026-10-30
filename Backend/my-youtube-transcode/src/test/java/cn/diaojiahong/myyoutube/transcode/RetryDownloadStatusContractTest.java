package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Batch;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Item;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryDownloadStatusContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(MediaRetryBatchStatus.class)
    void batchStatusKeepsItsWireValue(MediaRetryBatchStatus status) throws Exception {
        var batch = new Batch(1L, status, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
        String jackson = json.writeValueAsString(batch);
        String gson = JsonUtil.toJson(batch);
        assertEquals(status.getCode(), json.readTree(jackson).get("status").asText());
        assertEquals(status.getCode(), json.readTree(gson).get("status").asText());
        assertEquals(status, json.readValue(gson, Batch.class).status());
        assertEquals(status, JsonUtil.fromJson(jackson, Batch.class).status());
    }

    @ParameterizedTest
    @EnumSource(MediaRetryItemState.class)
    void itemStateKeepsItsWireValue(MediaRetryItemState state) throws Exception {
        var item = new Item(1L, state, 1, 2, null);
        String jackson = json.writeValueAsString(item);
        String gson = JsonUtil.toJson(item);
        assertEquals(state.getCode(), json.readTree(jackson).get("state").asText());
        assertEquals(state.getCode(), json.readTree(gson).get("state").asText());
        assertEquals(state, json.readValue(gson, Item.class).state());
        assertEquals(state, JsonUtil.fromJson(jackson, Item.class).state());
    }

    @ParameterizedTest
    @EnumSource(TranscodeTaskStatus.class)
    void downloadStatusKeepsItsWireValue(TranscodeTaskStatus status) throws Exception {
        TranscodeTaskVO task = TranscodeTaskVO.builder().taskId(1L).status(status).build();
        String jackson = json.writeValueAsString(task);
        String gson = JsonUtil.toJson(task);
        assertEquals(status.getCode(), json.readTree(jackson).get("status").asText());
        assertEquals(status.getCode(), json.readTree(gson).get("status").asText());
        assertEquals(status, json.readValue(gson, TranscodeTaskVO.class).getStatus());
        assertEquals(status, JsonUtil.fromJson(jackson, TranscodeTaskVO.class).getStatus());
    }
}
