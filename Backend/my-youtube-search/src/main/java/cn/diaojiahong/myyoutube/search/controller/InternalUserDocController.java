package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.search.api.UserDocPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * search 服务内部接口: 用户 ES 文档计数调整
 */
@RestController
@RequestMapping("/internal/user-doc")
@RequiredArgsConstructor
public class InternalUserDocController {

    private final ElasticsearchOperations elasticsearchOperations;

    /** 计数走 scripted update 原子自增, 读-改-写回存在并发下互相覆盖丢更新 */
    /** Math.max(int, long) 在 painless 里会把结果提升成 double 写回, 污染 long 字段, 用显式负值钳制 */
    private static final String ADJUST_VIDEO_COUNT_SCRIPT =
            "if (ctx._source.videoCount == null) { ctx._source.videoCount = params.delta } " +
            "else { ctx._source.videoCount += params.delta; if (ctx._source.videoCount < 0) { ctx._source.videoCount = 0 } }";
    private static final String ADJUST_LIKE_COUNT_SCRIPT =
            "if (ctx._source.likeCount == null) { ctx._source.likeCount = params.delta } " +
            "else { ctx._source.likeCount += params.delta }";

    @PostMapping("/video-count/adjust")
    public void adjustVideoCount(@RequestParam("userId") Long userId, @RequestParam("delta") Integer delta) {
        elasticsearchOperations.update(
                UpdateQuery.builder(String.valueOf(userId))
                        .withScriptType(ScriptType.INLINE)
                        .withScript(ADJUST_VIDEO_COUNT_SCRIPT)
                        .withParams(Map.of("delta", delta))
                        .withRetryOnConflict(3)
                        .build(),
                IndexCoordinates.of("user"));
    }

    @PostMapping("/like-count/adjust")
    public void adjustLikeCount(@RequestParam("userId") Long userId, @RequestParam("delta") Long delta) {
        elasticsearchOperations.update(
                UpdateQuery.builder(String.valueOf(userId))
                        .withScriptType(ScriptType.INLINE)
                        .withScript(ADJUST_LIKE_COUNT_SCRIPT)
                        .withParams(Map.of("delta", delta))
                        .withRetryOnConflict(3)
                        .build(),
                IndexCoordinates.of("user"));
    }

    @PostMapping("/like-count/adjust-batch")
    public void adjustLikeCountBatch(@RequestBody List<UserDocPort.LikeCountSync> syncs) {
        if (syncs == null || syncs.isEmpty()) {
            return;
        }
        List<UpdateQuery> queries = syncs.stream()
                .map(s -> UpdateQuery.builder(String.valueOf(s.userId()))
                        .withScriptType(ScriptType.INLINE)
                        .withScript(ADJUST_LIKE_COUNT_SCRIPT)
                        .withParams(Map.of("delta", s.delta()))
                        .withRetryOnConflict(3)
                        .build())
                .toList();
        elasticsearchOperations.bulkUpdate(queries, IndexCoordinates.of("user"));
    }
}
