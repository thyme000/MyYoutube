package cn.diaojiahong.myyoutube.common.utils;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;

/**
 *  工具类
 *  在threadLocal中保存用户信息
 */
public class UserContext {

    private static final ThreadLocal<UserDTO> TL = new ThreadLocal<>();

    public static void setUser(UserDTO user) {
        TL.set(user);
    }

    public static UserDTO getUser() {
        return TL.get();
    }

    public static void removeUser() {
        TL.remove();
    }
}
