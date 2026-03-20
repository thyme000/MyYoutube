package cn.diaojiahong.myyoutube.user.service.impl;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContest;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result<UserVO> getUserInfo() {
        UserDTO userDTO = UserContest.getUser();

        User user = getById(userDTO.getId());

        return Result.success(BeanUtil.toBean(user, UserVO.class));
    }
}
