package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合排序融合: N 路 RRF, 分数 = Σ 1/(k + rank), 只用名次不用原始分,
 * BM25 与余弦相似度量纲差异天然免疫; 同分按(路序, 名次)先到先得
 */
@Component
@RequiredArgsConstructor
public class RrfFuser {

    private final VectorProperties vectorProperties;

    public List<String> fuse(List<List<String>> recalls) {
        int k = Math.max(vectorProperties.getRrfK(), 1);
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < recalls.size(); i++) {
            int rank = 0;
            for (String id : recalls.get(i)) {
                scores.merge(id, 1.0 / (k + ++rank), Double::sum);
                firstSeen.putIfAbsent(id, i * 1_000_000 + rank);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : Integer.compare(firstSeen.get(a.getKey()), firstSeen.get(b.getKey()));
                })
                .map(Map.Entry::getKey)
                .toList();
    }
}
