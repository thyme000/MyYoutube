package cn.diaojiahong.myyoutube.search.recall;

import co.elastic.clients.elasticsearch._types.FieldValue;
import java.util.List;

public record RecallBatch(List<String> ids, List<FieldValue> nextCursor) {
    public static RecallBatch exhausted(List<String> ids) {
        return new RecallBatch(ids, List.of());
    }

    public boolean hasMore() {
        return !nextCursor.isEmpty();
    }
}
