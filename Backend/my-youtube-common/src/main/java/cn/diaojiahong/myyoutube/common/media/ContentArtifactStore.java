package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ContentArtifactStore {
    private final S3StorageUtil storage;
    public String write(Long id, String type, int attempt, Object value) {
        String key = "content/" + id + "/" + type.toLowerCase(java.util.Locale.ROOT) + "/" + attempt + "/" + UUID.randomUUID() + ".json";
        storage.uploadWithKey(JsonUtil.toJson(value).getBytes(StandardCharsets.UTF_8), key);
        return key;
    }
    public <T> T read(String key, Class<T> type) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("缺少产物对象键");
        return JsonUtil.fromJson(new String(storage.readObject(key), StandardCharsets.UTF_8), type);
    }
}
