package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容原始标签 -> 词库映射: slug 归一/置信度防线/新词护栏, 所有素材策略共用。
 * 打标素材由 transcode 的 TAG 任务计算, 本类只在资料接收时把名字落成 tag/content_tag
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagResultMapper {

    private static final int MAX_TAGS = 5;
    private static final int MAX_NEW_TAGS = 2;
    private static final int NEW_TAG_MIN_LEN = 2;
    private static final int NEW_TAG_MAX_LEN = 6;

    private final TagPoolHolder tagPoolHolder;
    private final ITagService tagService;

    /** 必填, 无默认: 与 AutoTagService 共用同一防线阈值 */
    @Value("${my-youtube.autotag.min-confidence}")
    private double minConfidence;

    public record TagConf(String name, double confidence) {
    }

    public record Mapped(String categoryCode, Map<Long, Double> weights, List<Tag> tags, List<String> names) {
    }

    /**
     * @param rawTagJson TAG 任务 resultJson
     */
    public static void validate(String rawTagJson) {
        JsonObject root = cn.diaojiahong.myyoutube.common.media.StructuredJson.object(rawTagJson);
        if (!root.has("category")
                || !root.has("tags")
                || !root.get("tags").isJsonArray()) {
            throw new IllegalArgumentException("Incomplete tag result");
        }
        if (java.util.Arrays.stream(VideoCategoryEnum.values())
                .noneMatch(c -> c.getCode().equals(root.get("category").getAsString())))
            throw new IllegalArgumentException("Invalid tag category");
        for (JsonElement value : root.getAsJsonArray("tags")) {
            JsonObject tag = value.getAsJsonObject();
            double confidence = tag.get("confidence").getAsDouble();
            if (tag.get("name").getAsString().isBlank()
                    || !Double.isFinite(confidence)
                    || confidence < 0
                    || confidence > 1)
                throw new IllegalArgumentException("Invalid tag value");
        }
    }

    public Mapped map(String rawTagJson) {
        validate(rawTagJson);
        String categoryCode = VideoCategoryEnum.OTHER.getCode();
        List<TagConf> confs = new ArrayList<>();
        try {
            JsonObject root = JsonUtil.GSON.fromJson(rawTagJson, JsonObject.class);
            if (root != null) {
                if (root.has("category") && !root.get("category").isJsonNull()) {
                    categoryCode = VideoCategoryEnum.fromCode(root.get("category").getAsString()).getCode();
                }
                if (root.has("tags") && root.get("tags").isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray("tags");
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        String name = o.has("name")
                                && !o.get("name").isJsonNull() ? o.get("name").getAsString().trim() : "";
                        double conf = o.has("confidence") && !o.get("confidence").isJsonNull()
                                ? o.get("confidence").getAsDouble() : 0;
                        if (!name.isEmpty()) {
                            confs.add(new TagConf(name, conf));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid TAG artifact", e);
        }

        confs.sort(Comparator.comparingDouble(TagConf::confidence).reversed());
        Map<Long, Double> weights = new LinkedHashMap<>();
        List<Tag> tags = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> seenSlugs = new HashSet<>();
        int newCount = 0;
        for (TagConf c : confs) {
            if (weights.size() >= MAX_TAGS || c.confidence() < minConfidence) {
                break;
            }
            String slug = StrUtils.generateSlug(c.name());
            if (slug.isEmpty() || !seenSlugs.add(slug)) {
                continue;
            }
            Tag tag = tagPoolHolder.findBySlug(slug);
            if (tag == null) tag = tagService.lambdaQuery().eq(Tag::getSlug, slug).one();
            if (tag != null && Integer.valueOf(0).equals(tag.getStatus())) continue;
            if (tag == null) {
                // 池外新词护栏: 限个数/限长度; sort_order=0 非种子, 无引用会被 rebuild 回收
                if (newCount >= MAX_NEW_TAGS
                        || c.name().length() < NEW_TAG_MIN_LEN || c.name().length() > NEW_TAG_MAX_LEN) {
                    continue;
                }
                tag = new Tag().setName(c.name()).setSlug(slug)
                        .setCategoryCode(categoryCode).setStatus(1).setSortOrder(0);
                try {
                    tagService.save(tag);
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    tag = tagService.lambdaQuery().eq(Tag::getSlug, slug).one();
                    if (tag == null) throw duplicate;
                }
                Tag committed = tag;
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                try {
                                    tagPoolHolder.register(committed, 0);
                                } catch (Exception error) {
                                    log.warn("Tag pool sync deferred tagId={}", committed.getId(), error);
                                }
                            }
                        });
                newCount++;
            }
            weights.put(tag.getId(), c.confidence());
            tags.add(tag);
            names.add(tag.getName());
        }
        return new Mapped(categoryCode, weights, tags, names);
    }
}
