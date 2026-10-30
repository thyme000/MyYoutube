package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentVectorDeadLetterService implements ContentVectorDeadLetterPort {
    private final ContentVectorDeadLetters deadLetters;
    private final ElasticsearchClient client;
    private final ContentVectorPublisher publisher;
    private final VectorProperties properties;

    private record Recovery(State state, String reason, String version) {
    }

    private static final Recovery UNKNOWN = new Recovery(State.UNKNOWN, "STATE_UNAVAILABLE", null);

    @Override
    public Page query(String cursor, Integer size) {
        ContentVectorDeadLetters.Page page = deadLetters.query(cursor, size);
        List<Long> ids = page.entries().stream().map(ContentVectorDeadLetters.Entry::contentId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Recovery> states = states(ids);
        List<Item> items = page.entries().stream().map(entry -> {
            Recovery recovery = entry.contentId() == null ? new Recovery(State.INVALID_MESSAGE, "INVALID_MESSAGE", null)
                    : recovery(entry, states);
            return new Item(
                    entry.reference(),
                    entry.messageId(),
                    entry.contentId(),
                    entry.storedAt(),
                    entry.reconsumeTimes(),
                    recovery.state(),
                    recovery.reason(),
                    recovery.version(),
                    entry.targetVersion(),
                    entry.failureReason());
        }).toList();
        return new Page(items, page.nextCursor(), properties.isEnabled());
    }

    @Override
    public ReplayResult replay(Reference reference, Long actorId) {
        // 管理员权限由 admin 入口校验，内部凭证由控制器校验；此处只保留操作者审计。
        if (actorId == null || actorId <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "操作者 ID 无效");
        }

        ContentVectorDeadLetters.Entry entry = deadLetters.find(reference);
        ReplayResult result;
        if (entry == null) result = new ReplayResult(ReplayStatus.NOT_FOUND, null, "死信不存在或已过期");
        else if (entry.contentId() == null) result = new ReplayResult(
                ReplayStatus.INVALID_MESSAGE,
                null,
                "消息格式错误，无法重投");
        else result = replay(entry);
        log.info("Content vector dead letter replay actorId={} broker={} queueId={} offset={} contentId={} result={}",
                actorId,
                reference.brokerName(),
                reference.queueId(),
                reference.offset(),
                result.contentId(),
                result.status());
        return result;
    }

    private ReplayResult replay(ContentVectorDeadLetters.Entry entry) {
        Long contentId = entry.contentId();
        Recovery recovery = recovery(entry, states(List.of(contentId)));
        if (recovery.state() == State.RECOVERED) return new ReplayResult(
                ReplayStatus.ALREADY_COMPLETED,
                contentId,
                "已有向量，无需重投");
        if (recovery.state() == State.UNKNOWN) return new ReplayResult(
                ReplayStatus.STATE_UNAVAILABLE,
                contentId,
                "当前内容状态不可用，请稍后重试");
        if (recovery.state() == State.INELIGIBLE) return new ReplayResult(
                ReplayStatus.INELIGIBLE,
                contentId,
                recovery.reason());
        if (!properties.isEnabled()) return new ReplayResult(ReplayStatus.DISABLED, contentId, "向量功能已关闭");
        try {
            if (entry.targetVersion() == null) publisher.publish(contentId);
            else publisher.publishRecovery(contentId, entry.targetVersion(), entry.failureReason());
            return new ReplayResult(ReplayStatus.ACCEPTED, contentId, "MQ 已接收，等待后台构建");
        } catch (Exception error) {
            log.warn("Content vector replay delivery unconfirmed contentId={}", contentId, error);
            return new ReplayResult(ReplayStatus.DELIVERY_UNCONFIRMED, contentId, "未获得 MQ 成功确认；消息可能已送达，可查询结果后重试");
        }
    }

    private Recovery recovery(ContentVectorDeadLetters.Entry entry, Map<Long, Recovery> states) {
        Recovery current = states.getOrDefault(entry.contentId(), UNKNOWN);
        if (current.state() == State.RECOVERED && entry.targetVersion() != null
                && !entry.targetVersion().equals(current.version())) {
            return new Recovery(State.UNRECOVERED, "TARGET_VERSION_MISMATCH", current.version());
        }
        return current;
    }

    private Map<Long, Recovery> states(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            MgetResponse<ContentDoc> response = client.mget(request -> request.index("content")
                    .ids(ids.stream().map(Object::toString).toList())
                    .sourceIncludes(List.of("title", "description", "summary", "status", "visibility", "vectorVersion")),
                    ContentDoc.class);
            Map<Long, Recovery> states = new HashMap<>();
            for (MultiGetResponseItem<ContentDoc> item : response.docs()) {
                if (item.isResult()) states.put(Long.valueOf(item.result().id()), recovery(item.result().source()));
            }
            return states;
        } catch (Exception error) {
            log.warn("Content vector recovery state unavailable", error);
            return Map.of();
        }
    }

    private Recovery recovery(ContentDoc content) {
        if (content == null) return new Recovery(State.INELIGIBLE, "CONTENT_MISSING", null);
        String version = content.getVectorVersion();
        if (!"published".equals(content.getStatus())) return new Recovery(State.INELIGIBLE, "NOT_PUBLISHED", version);
        if (!"public".equals(content.getVisibility())) return new Recovery(State.INELIGIBLE, "NOT_PUBLIC", version);
        boolean empty = Stream.of(content.getTitle(), content.getDescription(), content.getSummary())
                .allMatch(text -> text == null || text.isBlank());
        if (empty) return new Recovery(State.INELIGIBLE, "EMPTY_TEXT", version);
        return version != null && !version.isBlank() ? new Recovery(State.RECOVERED, null, version)
                : new Recovery(State.UNRECOVERED, null, null);
    }
}
