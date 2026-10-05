package cn.diaojiahong.myyoutube.common.chain;

import reactor.core.publisher.Mono;

/**
 * 通用责任链步骤: 各业务域提供自己的步骤与上下文, 按 {@link #group()} 聚合进注册表,
 * 同组内按 {@link #order()} 升序执行。步骤对上下文施加副作用并自行决定是否短路
 * (上下文自带决策语义, 框架只负责排序/遍历/短路调度, 不关心业务判定内容)。
 * 与当前上下文无关的步骤直接返回 Mono.empty()。
 *
 * @param <C> 本组步骤共享的上下文类型
 */
public interface ChainStep<C> {

    /** 分组标识: 同一业务域的步骤共用一个 group */
    String group();

    /** 组内执行顺序, 小者在前 */
    int order();

    Mono<Void> apply(C ctx);
}
