package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.recommend.mapper.UserTagPreferenceMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户标签偏好表（内容推荐用） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
public class UserTagPreferenceServiceImpl extends ServiceImpl<UserTagPreferenceMapper,
UserTagPreference> implements IUserTagPreferenceService {

    public UserTagPreferenceServiceImpl() {
    }

    @Override
    @Transactional
    public void saveUserTagPreference(List<Long> list, Long userId, EventEnum eventEnum) {
        if (list == null || list.isEmpty()) {
            return;
        }

        List<UserTagPreference> original = lambdaQuery().eq(UserTagPreference::getUserId, userId)
                .in(UserTagPreference::getTagId, list)
                .list();

        // 转换成map
        Map<Long, Integer> map = original.stream()
                .collect(Collectors.toMap(UserTagPreference::getTagId, UserTagPreference::getScore));
        Map<Long, Long> idMap = original.stream()
                .collect(Collectors.toMap(UserTagPreference::getTagId, UserTagPreference::getId));
        // 更新用户偏好标签数据
        List<UserTagPreference> result = new ArrayList<>(list.size());

        list.forEach(tagId -> {
            UserTagPreference preference = new UserTagPreference();
            preference.setUserId(userId);
            preference.setTagId(tagId);
            if (!map.isEmpty() && map.containsKey(tagId)) {
                preference.setScore(map.get(tagId) + eventEnum.getScore());
                preference.setId(idMap.get(tagId));
            } else {
                preference.setScore(eventEnum.getScore());
            }
            result.add(preference);
        });

        saveOrUpdateBatch(result);
    }
}
