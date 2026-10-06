package cn.diaojiahong.myyoutube.common.chain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * {@link ChainStepRegistry} 的同步版: 收集所有 {@link SyncChainStep} Bean,
 * 按分组聚合、组内按 order 升序, 执行时 stop 谓词为真即短路。
 * 加一种业务链 = 步骤类换个 group, 加一个步骤 = 新增实现类。
 */
public class SyncChainStepRegistry {

    private final Map<String, List<SyncChainStep<?>>> stepsByGroup;

    public SyncChainStepRegistry(List<SyncChainStep<?>> steps) {
        this.stepsByGroup = steps.stream()
                .collect(Collectors.groupingBy(SyncChainStep::group,
                        Collectors.collectingAndThen(Collectors.toList(),
                                list -> list.stream()
                                        .sorted(Comparator.comparingInt(SyncChainStep::order))
                                        .toList())));
    }

    /**
     * 取某组的有序步骤。C 与该组步骤声明的上下文类型由调用方保证一致
     * (Spring 无法按泛型注入 Map, 这里集中做一次不受检转换)。
     */
    @SuppressWarnings("unchecked")
    public <C> List<SyncChainStep<C>> steps(String group) {
        return (List<SyncChainStep<C>>) (List<?>) stepsByGroup.getOrDefault(group, List.of());
    }

    /**
     * 执行一组步骤: stop 谓词为真即短路(通常来自上下文的决策状态)。
     * fail-fast 型步骤直接抛异常终止; "成功短路"型步骤把结果放进上下文
     * 并让 stop 谓词命中, 调用方随后读上下文取结果。
     */
    public <C> void execute(String group, C ctx, Predicate<C> stop) {
        for (SyncChainStep<C> step : this.<C>steps(group)) {
            if (stop.test(ctx)) {
                return;
            }
            step.apply(ctx);
        }
    }

    /** 无"成功短路"语义的链(fail-fast 即唯一终止方式)用这个重载 */
    public <C> void execute(String group, C ctx) {
        execute(group, ctx, c -> false);
    }

    public boolean hasGroup(String group) {
        return stepsByGroup.containsKey(group);
    }
}
