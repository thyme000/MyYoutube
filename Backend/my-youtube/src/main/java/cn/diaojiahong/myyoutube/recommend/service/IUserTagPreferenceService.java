package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户标签偏好表（内容推荐用） 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface IUserTagPreferenceService extends IService<UserTagPreference> {

    void saveUserTagPreferenceAsync(List<Long> list, Long userId, EventEnum eventEnum);
}
