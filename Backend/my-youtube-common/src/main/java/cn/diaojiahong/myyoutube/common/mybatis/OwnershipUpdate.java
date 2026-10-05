package cn.diaojiahong.myyoutube.common.mybatis;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.util.function.Consumer;

/**
 * 属主条件更新模板: id + 属主双条件 UPDATE, 影响行数为 0 即抛业务异常。
 * 状态机类操作(删除/私密/公开/评论开关)统一走这里, 属主条件与失败判定只写这一份。
 */
public final class OwnershipUpdate {

    private OwnershipUpdate() {
    }

    public static <T> void updateOrFail(BaseMapper<T> mapper,
                                        SFunction<T, ?> idColumn, Long id,
                                        SFunction<T, ?> ownerColumn, Long ownerId,
                                        Consumer<LambdaUpdateWrapper<T>> setter,
                                        String failMessage) {
        LambdaUpdateWrapper<T> wrapper = new LambdaUpdateWrapper<T>()
                .eq(idColumn, id)
                .eq(ownerColumn, ownerId);
        setter.accept(wrapper);
        if (mapper.update(null, wrapper) <= 0) {
            throw new BizException(BizException.BIZ_ERROR, failMessage);
        }
    }
}
