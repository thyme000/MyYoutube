package cn.diaojiahong.myyoutube.common.page;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

/** 统一游标传输格式；复用 JsonUtil 保持时间和大整数精度，业务约束由调用方校验。 */
public final class CursorCodec<T> {
    private final Class<T> type;
    private final int maxLength;
    private final String invalidMessage;

    public CursorCodec(Class<T> type, int maxLength, String invalidMessage) {
        this.type = type;
        this.maxLength = maxLength;
        this.invalidMessage = invalidMessage;
    }

    public String encode(T cursor) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JsonUtil.toJson(cursor).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 自动检查长度、Base64URL／JSON 格式及解析结果非空；字段取值和查询条件匹配由 validate 回调校验。
     * 未传或空白游标表示首批，返回 null，不执行回调。
     */
    public T decode(String value, Consumer<T> validate) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > maxLength) throw new IllegalArgumentException();
            String json = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            T cursor = JsonUtil.fromJson(json, type);
            if (cursor == null) throw new IllegalArgumentException();
            validate.accept(cursor);
            return cursor;
        } catch (BizException rejected) {
            throw rejected;
        } catch (RuntimeException invalid) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, invalidMessage, invalid);
        }
    }
}
