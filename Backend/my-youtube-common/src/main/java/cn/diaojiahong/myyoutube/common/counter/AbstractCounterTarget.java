package cn.diaojiahong.myyoutube.common.counter;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 计数落库目标的通用骨架: 增量与覆盖都落在「单表 + 单计数列」上,
 * 差异只有 Service/id 列/计数列三个点, 权威值来源各模块自理。
 * 子类只负责 type() 与 authoritativeValue()。
 */
public abstract class AbstractCounterTarget<T> implements CounterTarget {

    private final IService<T> service;
    private final SFunction<T, ?> idColumn;
    private final SFunction<T, ?> countColumn;

    protected AbstractCounterTarget(IService<T> service, SFunction<T, ?> idColumn, SFunction<T, ?> countColumn) {
        this.service = service;
        this.idColumn = idColumn;
        this.countColumn = countColumn;
    }

    @Override
    public void applyDelta(long id, long delta) {
        guardedDelta(service, idColumn, id, countColumn, delta);
    }

    @Override
    public void overwrite(long id, long value) {
        service.lambdaUpdate().eq(idColumn, id).set(countColumn, value).update();
    }

    /**
     * 守护式增量: 负增量时加"当前值够减"护底, 计数不许为负。
     *
     * @return 是否更新到行。负增量被护底拦住时返回 false，调用方不得再记派生计数
     */
    public static <T> boolean guardedDelta(IService<T> service, SFunction<T, ?> idColumn, long id,
                                           SFunction<T, ?> countColumn, long delta) {
        LambdaUpdateChainWrapper<T> wrapper = service.lambdaUpdate()
                .eq(idColumn, id)
                .setIncrBy(countColumn, delta);
        if (delta < 0) {
            wrapper.ge(countColumn, -delta);
        }
        return wrapper.update();
    }
}
