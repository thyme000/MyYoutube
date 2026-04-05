package cn.diaojiahong.myyoutube.common.utils;

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

    public static void removeUser() {
        TL.remove();
    }
}
