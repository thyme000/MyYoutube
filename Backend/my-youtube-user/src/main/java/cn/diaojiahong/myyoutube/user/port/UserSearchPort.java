package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.user.domain.po.User;

/**
 * 用户搜索文档同步端口: 由 search 模块提供实现
 */
public interface UserSearchPort {

    /**
     * 用户资料变更后同步 ES: 更新其视频文档的作者昵称, 并重建用户文档
     */
    void onUserProfileChanged(User user);
}
