package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;

/**
 *  工具类
 *  在threadLocal中保存用户信息
 */
public class UserContext {

    private static final ThreadLocal<String> TL = new ThreadLocal<>();

    public static void setUser(String userId) {
        TL.set(userId);
    }

    public static String getUserId() {
        return TL.get();
    }

    public static Long requireUserId() {
        String value = getUserId();
        if (value != null) {
            try {
                long userId = Long.parseLong(value);
                if (userId > 0) {
                    return userId;
                }
            } catch (NumberFormatException ignored) {
                // 身份头无法解析时按未认证处理。
            }
        }
        throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录或身份无效");
    }

    public static void removeUser() {
        TL.remove();
    }
}
