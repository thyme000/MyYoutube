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

    private static final String ADJUST_LIKE_COUNT_SCRIPT =
            "if (ctx._source.likeCount == null) { ctx._source.likeCount = params.delta } " +
            "else { ctx._source.likeCount += params.delta }";

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
