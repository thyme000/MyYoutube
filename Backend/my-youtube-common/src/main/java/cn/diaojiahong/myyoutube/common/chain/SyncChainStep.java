package cn.diaojiahong.myyoutube.common.chain;

/**
 * {@link ChainStep} 的同步版: 供 servlet 栈业务服务使用, 不引入 Reactor API。
 * 分组/排序/短路语义与响应式版一致, 步骤对上下文施加副作用并自行决定是否短路。
 *
 * @param <C> 本组步骤共享的上下文类型
 */
public interface SyncChainStep<C> {

    /** 分组标识: 同一业务域的步骤共用一个 group */
    String group();

    /** 组内执行顺序, 小者在前 */
    int order();

    void apply(C ctx);
}
