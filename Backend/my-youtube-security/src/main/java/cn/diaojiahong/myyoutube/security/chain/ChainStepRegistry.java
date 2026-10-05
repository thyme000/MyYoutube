package cn.diaojiahong.myyoutube.security.chain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 责任链注册表: 借助 Spring 收集所有 {@link ChainStep} Bean, 按分组标识聚合成 Map,
 * 同组内按 order 升序。执行时逐组取步骤短路遍历 —— 加一种业务链 = 步骤类换个 group,
 * 加一个步骤 = 新增实现类, 执行骨架不再复制。
 * 经 ChainAutoConfiguration 装配, 步骤 Bean 由各使用方所在包提供。
 */
public class ChainStepRegistry {

    private final Map<String, List<ChainStep<?>>> stepsByGroup;

    public ChainStepRegistry(List<ChainStep<?>> steps) {
        this.stepsByGroup = steps.stream()
                .collect(Collectors.groupingBy(ChainStep::group,
                        Collectors.collectingAndThen(Collectors.toList(),
                                list -> list.stream()
                                        .sorted(Comparator.comparingInt(ChainStep::order))
                                        .toList())));
    }

    /**
     * 取某组的有序步骤。C 与该组步骤声明的上下文类型由调用方保证一致
     * (Spring 无法按泛型注入 Map, 这里集中做一次不受检转换)。
     */
    @SuppressWarnings("unchecked")
    public <C> List<ChainStep<C>> steps(String group) {
        return (List<ChainStep<C>>) (List<?>) stepsByGroup.getOrDefault(group, List.of());
    }

    /**
     * 执行一组步骤: stop 谓词为真即短路(通常来自上下文的决策状态)。
     * 调用方在 then(Mono.defer(...)) 里读取执行后的上下文落地决策 ——
     * 注意 defer 不能省, then 的参数在组装期就会求值。
     */
    public <C> Mono<Void> execute(String group, C ctx, Predicate<C> stop) {
        return Flux.fromIterable(steps(group))
                .concatMap(step -> stop.test(ctx) ? Mono.empty() : step.apply(ctx))
                .then();
    }

    public boolean hasGroup(String group) {
        return stepsByGroup.containsKey(group);
    }
}
