package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.llm.LlmClient;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机器打标编排: 信号加载(策略) → LLM 受控选标 → 防线过滤 → 先成功后写(关联+类目+缓存) → ES 全量同步。
 * 本服务是 content ES 文档的唯一全量写者, 打标失败也要发无标签兜底文档保可搜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTagService {

    private static final int MAX_TAGS = 5;
    private static final int MAX_NEW_TAGS = 2;
    private static final int NEW_TAG_MIN_LEN = 2;
    private static final int NEW_TAG_MAX_LEN = 6;
    private static final String PROMPT_CATEGORIES =
            "GAME(游戏) MOVIE(影视) MUSIC(音乐) KNOWLEDGE(知识) LIFE(生活) TECH(科技) ENTERTAINMENT(娱乐) OTHER(其他)";

    private final TaggingStrategyRegistry registry;
    private final LlmClient llmClient;
    private final TagPoolHolder tagPoolHolder;
    private final ITagService tagService;
    private final IContentTagService contentTagService;
    private final EsSyncPublisher esSyncPublisher;
    private final StringRedisTemplate redisTemplate;

    @Value("${my-youtube.llm.min-confidence:0.5}")
    private double minConfidence;

    /**
     * @return 是否成功产出机器标签
     */
    public boolean tagContent(Long contentId, ContentTypeEnum type) {
        ContentTaggingStrategy strategy = registry.get(type);
        if (strategy == null) {
            log.warn("无打标策略, type={}", type);
            return false;
        }
        ContentTaggingStrategy.TagSignal signal = strategy.loadSignal(contentId);
        if (signal == null) {
            log.info("内容不存在或无打标信号, contentId={}", contentId);
            return false;
        }

        TaggingResult result = null;
        if (llmClient.isConfigured()) {
            result = tagWithRetry(signal);
        } else {
            log.warn("LLM api-key 未配置, 跳过打标, contentId={}", contentId);
        }

        if (result != null && !result.tagWeights().isEmpty()) {
            persistResult(strategy, contentId, result);
        }
        // 唯一全量写者: 打标成败都发当前状态文档, 失败时无标签兜底保可搜
        sendEs(strategy, contentId, result == null ? List.of() : List.copyOf(result.tagWeights().keySet()));
        return result != null && !result.tagWeights().isEmpty();
    }

    private TaggingResult tagWithRetry(ContentTaggingStrategy.TagSignal signal) {
        List<LlmClient.Message> prompt = buildPrompt(signal);
        for (int i = 1; i <= 2; i++) {
            try {
                return parse(llmClient.chat(prompt));
            } catch (Exception e) {
                log.warn("LLM 打标解析失败(第{}次): {}", i, e.getMessage());
            }
        }
        return null;
    }

    private List<LlmClient.Message> buildPrompt(ContentTaggingStrategy.TagSignal signal) {
        StringBuilder pool = new StringBuilder();
        Map<String, List<Tag>> grouped = tagPoolHolder.grouped();
        for (VideoCategoryEnum c : VideoCategoryEnum.values()) {
            appendPoolLine(pool, c.getLabel(), grouped.get(c.getCode()));
        }
        appendPoolLine(pool, "通用", grouped.get(""));

        String system = """
                你是内容平台的自动打标器。任务: 判断内容所属类目, 并挑选标签。
                规则:
                1. 类目只能从【类目表】中选一个, 用括号外的 CODE 返回;
                2. 标签优先从【候选标签】中选择, 每个标签给出 0~1 的置信度;
                3. 候选确实都不贴切时可自创新标签, 最多 2 个, 每个 2~6 个字, 是规范名词, 禁止摘抄标题片段;
                4. 标签总数控制在 3~5 个;
                5. 只输出 JSON, 不要任何解释, 格式: {"category":"CODE","tags":[{"name":"标签名","confidence":0.9}]}""";

        String user = "【类目表】" + PROMPT_CATEGORIES
                + "\n【候选标签】\n" + pool
                + "\n【标题】" + signal.title()
                + "\n【简介】" + signal.description();
        return List.of(new LlmClient.Message("system", system), new LlmClient.Message("user", user));
    }

    private void appendPoolLine(StringBuilder pool, String label, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        pool.append("- ").append(label).append(": ")
                .append(tags.stream().map(Tag::getName).collect(java.util.stream.Collectors.joining(", ")))
                .append('\n');
    }

    private TaggingResult parse(String content) {
        JsonObject root = JsonUtil.GSON.fromJson(stripFences(content), JsonObject.class);

        String rawCategory = root.has("category") && !root.get("category").isJsonNull()
                ? root.get("category").getAsString() : VideoCategoryEnum.OTHER.getCode();
        String categoryCode = VideoCategoryEnum.fromCode(rawCategory).getCode();

        Map<Long, Double> weights = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        if (root.has("tags") && root.get("tags").isJsonArray()) {
            List<Choice> choices = new ArrayList<>();
            JsonArray arr = root.getAsJsonArray("tags");
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString().trim() : "";
                double conf = o.has("confidence") && !o.get("confidence").isJsonNull() ? o.get("confidence").getAsDouble() : 0;
                if (!name.isEmpty()) {
                    choices.add(new Choice(name, conf));
                }
            }
            choices.sort(Comparator.comparingDouble(Choice::confidence).reversed());

            Set<String> seenSlugs = new HashSet<>();
            int newCount = 0;
            for (Choice c : choices) {
                if (weights.size() >= MAX_TAGS || c.confidence() < minConfidence) {
                    break;
                }
                String slug = StrUtils.generateSlug(c.name());
                if (slug.isEmpty() || !seenSlugs.add(slug)) {
                    continue;
                }
                Tag tag = tagPoolHolder.findBySlug(slug);
                if (tag == null) {
                    // 池外新词护栏: 限个数/限长度, 继承所选类目; sort_order=0 非种子, 无引用会被 rebuild 回收
                    if (newCount >= MAX_NEW_TAGS
                            || c.name().length() < NEW_TAG_MIN_LEN || c.name().length() > NEW_TAG_MAX_LEN) {
                        continue;
                    }
                    tag = new Tag().setName(c.name()).setSlug(slug)
                            .setCategoryCode(categoryCode).setStatus(1).setSortOrder(0);
                    tagService.save(tag);
                    newCount++;
                }
                weights.put(tag.getId(), c.confidence());
                names.add(tag.getName());
            }
        }
        return new TaggingResult(categoryCode, weights, names);
    }

    private String stripFences(String content) {
        String t = content.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").trim();
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end).trim();
            }
        }
        return t;
    }

    /** 先成功后写: LLM 产出齐备才动库; 失败路径不触碰任何表 */
    private void persistResult(ContentTaggingStrategy strategy, Long contentId, TaggingResult result) {
        List<ContentTag> old = contentTagService.lambdaQuery()
                .eq(ContentTag::getContentId, contentId)
                .list();
        contentTagService.saveAutoTags(contentId, result.tagWeights());
        strategy.persistResult(contentId, result.categoryCode(), String.join(",", result.names()));
        adjustRefCount(old, result.tagWeights().keySet());
    }

    /** 引用计数只活在 Redis(ZSET), 打标是 content_tag 唯一写入方, 增减即守恒 */
    private void adjustRefCount(List<ContentTag> old, Set<Long> now) {
        Set<Long> oldIds = old.stream().map(ContentTag::getTagId).collect(java.util.stream.Collectors.toSet());
        for (Long tagId : now) {
            if (!oldIds.contains(tagId)) {
                redisTemplate.opsForZSet().incrementScore(RedisConstants.CONTENT_TAG_REF, tagId.toString(), 1);
            }
        }
        for (Long tagId : oldIds) {
            if (!now.contains(tagId)) {
                redisTemplate.opsForZSet().incrementScore(RedisConstants.CONTENT_TAG_REF, tagId.toString(), -1);
            }
        }
    }

    private void sendEs(ContentTaggingStrategy strategy, Long contentId, List<Long> tagIds) {
        try {
            ContentDocMessage message = strategy.buildEsMessage(contentId, tagIds);
            if (message != null) {
                esSyncPublisher.syncContentDoc(message);
            }
        } catch (Exception e) {
            // ES 同步失败不回滚打标结果, 也不触发 MQ 重试(重试会重复调 LLM); 夜巡/回刷兜底
            log.warn("ES 全量同步失败, contentId={}", contentId, e);
        }
    }

    private record Choice(String name, double confidence) {
    }

    private record TaggingResult(String categoryCode, Map<Long, Double> tagWeights, List<String> names) {
    }
}
