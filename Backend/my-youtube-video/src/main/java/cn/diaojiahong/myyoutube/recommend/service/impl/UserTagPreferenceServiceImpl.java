package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.recommend.mapper.UserTagPreferenceMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户标签偏好表（内容推荐用） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
public class UserTagPreferenceServiceImpl extends ServiceImpl<UserTagPreferenceMapper, UserTagPreference>
        implements IUserTagPreferenceService {

    @Override
    public void addBehaviorScore(Long userId, Long contentId, EventEnum event) {
        baseMapper.addBehaviorScore(userId, contentId, event.getScore());
    }
}
