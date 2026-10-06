package cn.diaojiahong.myyoutube.recommend.cf;

import cn.diaojiahong.myyoutube.recommend.domain.enums.SimAlgorithmEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.ItemSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swing(阿里): sim(i,j) = Σ_{(u,v)∈U(i)×U(j)} 1/(α + |I(u)∩I(v)|)
 * 两个用户交集越小, 这对用户的共现越"专属"、越可信; 活跃用户(大交集)贡献被压平。
 */
@Component
public class SwingCalculator implements SimilarityCalculator {

    /**
     * 平滑因子: 交集为 2 时贡献 1/3, 交集越大贡献越趋近 0
     */
    private static final double ALPHA = 1.0;

    /**
     * 单内容的最大参与用户数, 防止热门内容上用户对数量平方级爆炸
     */
    private static final int MAX_USERS_PER_ITEM = 300;

    private final SimComputePool pool;

    public SwingCalculator(SimComputePool pool) {
        this.pool = pool;
    }

    @Override
    public SimAlgorithmEnum algorithm() {
        return SimAlgorithmEnum.SWING;
    }

    /**
     * 任务 = 单个 target 的用户对展开, 不同 target 写不同 key, 各 worker 无冲突;
     * 按 target 用户数降序, 让最重的任务先被领走。
     */
    private record TargetTask(long target, List<Long> users) {
    }

    @Override
    public List<ItemSimilarity> compute(Map<Long, Map<Long, Double>> userItemWeights) {
        // Swing 只关心"有没有", 不看分值: 构建 内容 -> 用户列表
        Map<Long, List<Long>> itemUsers = new HashMap<>();
        userItemWeights.forEach((userId, items) -> items.keySet().forEach(contentId ->
                itemUsers.computeIfAbsent(contentId, k -> new ArrayList<>()).add(userId)));

        List<TargetTask> tasks = new ArrayList<>(itemUsers.size());
        itemUsers.forEach((target, users) -> tasks.add(new TargetTask(target,
                users.size() > MAX_USERS_PER_ITEM ? users.subList(0, MAX_USERS_PER_ITEM) : users)));
        tasks.sort(Comparator.comparingInt((TargetTask t) -> t.users().size()).reversed());

        Map<Long, Map<Long, Double>> swing = pool.collectAndMerge(
                tasks,
                HashMap::new,
                (local, task) -> accumulate(task, userItemWeights, local),
                (a, b) -> {
                    // target 互不重叠, 整体换入即可
                    a.putAll(b);
                    return a;
                });

        return toRows(swing);
    }

    private void accumulate(TargetTask task, Map<Long, Map<Long, Double>> userItemWeights,
                            Map<Long, Map<Long, Double>> local) {
        List<Long> users = task.users();
        long target = task.target();
        for (int a = 0; a < users.size(); a++) {
            Set<Long> itemsA = userItemWeights.get(users.get(a)).keySet();
            for (int b = a + 1; b < users.size(); b++) {
                Set<Long> itemsB = userItemWeights.get(users.get(b)).keySet();
                // 拷贝较小一方做交集, 省一次大集合拷贝
                Set<Long> common = new HashSet<>(itemsA.size() <= itemsB.size() ? itemsA : itemsB);
                common.retainAll(itemsA.size() <= itemsB.size() ? itemsB : itemsA);
                if (common.size() < 2) {
                    // 至少要同时含 target 和某个邻居 j, 共现才成立
                    continue;
                }
                double contribution = 1.0 / (ALPHA + common.size());
                for (Long j : common) {
                    if (j == target) {
                        continue;
                    }
                    local.computeIfAbsent(target, k -> new HashMap<>()).merge(j, contribution, Double::sum);
                }
            }
        }
    }

    private List<ItemSimilarity> toRows(Map<Long, Map<Long, Double>> swing) {
        List<ItemSimilarity> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, Double>> entry : swing.entrySet()) {
            long i = entry.getKey();
            for (Map.Entry<Long, Double> neighbor : entry.getValue().entrySet()) {
                long j = neighbor.getKey();
                if (i > j) {
                    // 贡献 swing[i][j] 与 swing[j][i] 来自同一批用户对, 数学上对称
                    continue;
                }
                rows.add(newRow(i, j, neighbor.getValue()));
                rows.add(newRow(j, i, neighbor.getValue()));
            }
        }
        return rows;
    }

    private ItemSimilarity newRow(long itemA, long itemB, double score) {
        return new ItemSimilarity()
                .setAlgorithm(algorithm())
                .setItemA(itemA)
                .setItemB(itemB)
                .setScore(score);
    }
}
