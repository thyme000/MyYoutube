package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打标维护: 存量回刷 / 夜巡补打 / 垃圾标签下线 / 偏好死行清理 / 引用计数重建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTagMaintenanceService {

    private static final int PAGE_SIZE = 200;
    /** sort_order 达到该值的为种子词, 垃圾回收豁免 */
    private static final int SEED_SORT_ORDER = 100;

    private final AutoTagService autoTagService;
    private final ContentMapper contentMapper;
    private final VideoMapper videoMapper;
    private final DynamicMapper dynamicMapper;
    private final ITagService tagService;
    private final IContentTagService contentTagService;
    private final IUserTagPreferenceService userTagPreferenceService;
    private final TagPoolHolder tagPoolHolder;
    private final StringRedisTemplate redisTemplate;

    /**
     * 全量重打(先成功后写: LLM 产出齐备才覆盖, 失败内容原样保留)。
     * 默认跳过「已有关联且类目已回写」的内容, 中断重跑可续、不重复烧 LLM;
     * force=true 强制逐条重打, 词库/提示词升级后刷新存量标签用。
     * 收尾: 无引用且非种子的历史标签下线, 偏好表死标签行清理, 引用计数 ZSET 重建。
     */
    public Map<String, Integer> rebuildAll(boolean force) {
        int total = 0;
        int tagged = 0;
        int skippedTagged = 0;
        Long cursor = 0L;
        while (true) {
            List<Content> batch = contentMapper.selectList(new LambdaQueryWrapper<Content>()
                    .in(Content::getType, ContentTypeEnum.VIDEO, ContentTypeEnum.DYNAMIC)
                    .gt(cursor != null, Content::getId, cursor)
                    .orderByAsc(Content::getId)
                    .last("LIMIT " + PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            Set<Long> taggedIds = force ? Set.of() : loadIdsWithTag(batch);
            Set<Long> categorizedIds = force ? Set.of() : loadIdsWithCategory(batch);
            for (Content content : batch) {
                cursor = content.getId();
                total++;
                if (taggedIds.contains(content.getId()) && categorizedIds.contains(content.getId())) {
                    skippedTagged++;
                    continue;
                }
                if (autoTagService.tagContent(content.getId(), content.getType())) {
                    tagged++;
                } else {
                    log.info("回刷跳过 contentId={}, type={}", content.getId(), content.getType());
                }
            }
        }

        int deactivated = deactivateGarbageTags();
        int cleanedPrefs = cleanDeadPreferences();
        rebuildRefCounter();

        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("tagged", tagged);
        stats.put("skippedTagged", skippedTagged);
        stats.put("skipped", total - tagged - skippedTagged);
        stats.put("deactivatedTags", deactivated);
        stats.put("cleanedPreferences", cleanedPrefs);
        log.info("打标全量回刷完成: force={}, {}", force, stats);
        return stats;
    }

    /** 批内已有关联的内容 id: 已打过的判定一半 */
    private Set<Long> loadIdsWithTag(List<Content> batch) {
        List<Long> ids = batch.stream().map(Content::getId).toList();
        return contentTagService.lambdaQuery()
                .select(ContentTag::getContentId)
                .in(ContentTag::getContentId, ids)
                .list().stream()
                .map(ContentTag::getContentId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 批内类目已回写的内容 id(类目在领域表, 不在 content): 已打过的判定另一半 */
    private Set<Long> loadIdsWithCategory(List<Content> batch) {
        Set<Long> result = new HashSet<>();
        List<Long> videoIds = batch.stream()
                .filter(c -> c.getType() == ContentTypeEnum.VIDEO).map(Content::getId).toList();
        List<Long> dynamicIds = batch.stream()
                .filter(c -> c.getType() == ContentTypeEnum.DYNAMIC).map(Content::getId).toList();
        if (!videoIds.isEmpty()) {
            for (Video v : videoMapper.selectList(new LambdaQueryWrapper<Video>()
                    .select(Video::getId, Video::getCategory)
                    .in(Video::getId, videoIds))) {
                if (v.getCategory() != null) {
                    result.add(v.getId());
                }
            }
        }
        if (!dynamicIds.isEmpty()) {
            for (Dynamic d : dynamicMapper.selectList(new LambdaQueryWrapper<Dynamic>()
                    .select(Dynamic::getId, Dynamic::getCategoryCode)
                    .in(Dynamic::getId, dynamicIds))) {
                if (d.getCategoryCode() != null) {
                    result.add(d.getId());
                }
            }
        }
        return result;
    }

    /**
     * 夜巡: 补打「无标签或无类目」的视频/动态, 自愈 MQ 丢失与 LLM 长故障。
     * 空信号内容会被扫到但打不动, 每晚空扫一遍量小可接受。
     */
    public void patrol() {
        List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .select(Video::getId)
                .eq(Video::getIsDelete, 0)
                .and(w -> w.isNull(Video::getCategory)
                        .or().notExists("SELECT 1 FROM content_tag ct WHERE ct.content_id = video.id")));
        List<Dynamic> dynamics = dynamicMapper.selectList(new LambdaQueryWrapper<Dynamic>()
                .select(Dynamic::getId)
                .eq(Dynamic::getIsDelete, 0)
                .and(w -> w.isNull(Dynamic::getCategoryCode)
                        .or().notExists("SELECT 1 FROM content_tag ct WHERE ct.content_id = dynamic.id")));

        int tagged = 0;
        for (Video v : videos) {
            if (autoTagService.tagContent(v.getId(), ContentTypeEnum.VIDEO)) {
                tagged++;
            }
        }
        for (Dynamic d : dynamics) {
            if (autoTagService.tagContent(d.getId(), ContentTypeEnum.DYNAMIC)) {
                tagged++;
            }
        }
        log.info("打标夜巡完成: 待补 video={} dynamic={}, 补打成功={}", videos.size(), dynamics.size(), tagged);
    }

    /** 无引用且非种子的标签下线(标题切词时代与 LLM 弃词), 行保留可追溯, 词库池同步摘除 */
    private int deactivateGarbageTags() {
        List<Tag> garbage = tagService.lambdaQuery()
                .eq(Tag::getStatus, 1)
                .lt(Tag::getSortOrder, SEED_SORT_ORDER)
                .notExists("SELECT 1 FROM content_tag ct WHERE ct.tag_id = tag.id")
                .list();
        if (garbage.isEmpty()) {
            return 0;
        }
        tagService.lambdaUpdate()
                .in(Tag::getId, garbage.stream().map(Tag::getId).toList())
                .set(Tag::getStatus, 0)
                .update();
        garbage.forEach(tagPoolHolder::removeFromPool);
        return garbage.size();
    }

    /** 偏好表中标签已无任何内容引用的死行清理 */
    private int cleanDeadPreferences() {
        List<Long> deadTagIds = userTagPreferenceService.lambdaQuery()
                .select(UserTagPreference::getTagId)
                .notExists("SELECT 1 FROM content_tag ct WHERE ct.tag_id = user_tag_preference.tag_id")
                .list().stream().map(UserTagPreference::getTagId).distinct().toList();
        if (deadTagIds.isEmpty()) {
            return 0;
        }
        userTagPreferenceService.lambdaUpdate()
                .in(UserTagPreference::getTagId, deadTagIds)
                .remove();
        return deadTagIds.size();
    }

    /** 全局引用计数 + 词库池以 content_tag/tag 实况重建, 顺带校正事件式增减的热度漂移 */
    private void rebuildRefCounter() {
        redisTemplate.delete(RedisConstants.CONTENT_TAG_REF);
        List<Map<String, Object>> rows = contentTagService.getBaseMapper()
                .selectMaps(new QueryWrapper<ContentTag>()
                        .select("tag_id", "COUNT(*) AS cnt")
                        .groupBy("tag_id"));
        Map<Long, Long> refCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object tagId = row.get("tag_id");
            Object cnt = row.get("cnt");
            if (tagId != null && cnt != null) {
                refCounts.put(Long.parseLong(tagId.toString()), Long.parseLong(cnt.toString()));
                redisTemplate.opsForZSet().add(RedisConstants.CONTENT_TAG_REF,
                        tagId.toString(), Double.parseDouble(cnt.toString()));
            }
        }
        tagPoolHolder.rebuildAll(tagService.lambdaQuery().eq(Tag::getStatus, 1).list(), refCounts);
    }
}
