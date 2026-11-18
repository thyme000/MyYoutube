package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import cn.diaojiahong.myyoutube.recommend.mapper.UserVideoBehaviorMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户-视频行为流水表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
public class UserVideoBehaviorServiceImpl extends ServiceImpl<UserVideoBehaviorMapper, UserVideoBehavior> implements IUserVideoBehaviorService {
}
