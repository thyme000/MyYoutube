package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.Item;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.ItemRole;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class PreparationContractsTest {
    private final ObjectMapper jackson = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(ItemRole.class)
    void roleKeepsItsWireCodeAcrossGsonStorageAndJacksonHttp(ItemRole role) throws Exception {
        var item = new Item(role, 0, "source", "prepared", 0, 0, 0, 0, null);

        String stored = JsonUtil.toJson(item);
        String response = jackson.writeValueAsString(item);

        assertEquals(role.name(), jackson.readTree(stored).get("role").asText());
        assertEquals(role.name(), jackson.readTree(response).get("role").asText());
        assertEquals(item, JsonUtil.fromJson(stored, Item.class));
        assertEquals(item, jackson.readValue(response, Item.class));
    }

    @Test
    void unknownRoleCannotEnterThePreparationModel() {
        String json = """
                {"role":"UNKNOWN","index":0,"originalObjectKey":"source","objectKey":"prepared"}
                """;

        assertThrows(RuntimeException.class, () -> JsonUtil.fromJson(json, Item.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> jackson.readValue(json, Item.class));
    }
}
