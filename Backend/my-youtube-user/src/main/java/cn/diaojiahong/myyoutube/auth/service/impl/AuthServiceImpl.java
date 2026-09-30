package cn.diaojiahong.myyoutube.auth.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.PasswordEncoder;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.auth.service.IAuthService;
import cn.diaojiahong.myyoutube.search.api.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {

    private final IUserService userService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public Result<String> login(LoginRequestDTO loginRequestDTO) {
        String username = loginRequestDTO.getUsername();
        String rawPassword = loginRequestDTO.getPassword();

        // 查询用户
        User user = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .one();
        if (user == null) {
            return Result.fail("用户不存在!");
        }

        // BCrypt 校验：matches(明文, 哈希)，第一个参数必须是原始明文
        if (!PasswordEncoder.matches(rawPassword, user.getPassword())) {
            return Result.fail("密码错误!");
        }


        // 生成 token，将 userId 关键字段以 字符串 形式存入 Redis
        String token = UUID.randomUUID().toString();
        String tokenKey = RedisConstants.USER_TOKEN + token;

        redisTemplate.opsForValue().set(tokenKey, String.valueOf(user.getId()));
        redisTemplate.expire(tokenKey, RedisConstants.USER_TOKEN_EXPIRE, TimeUnit.HOURS);

        log.info("用户 [{}] 登录成功", username);
        return Result.success(token);
    }

    @Override
    public Result<String> register(RegisterRequestDTO registerRequestDTO) {
        // 两次密码一致性校验
        if (!registerRequestDTO.getPassword().equals(registerRequestDTO.getConfirmPassword())) {
            return Result.fail("两次密码不一致!");
        }


        // 密码格式校验
        if (RegexUtils.isPasswordInvalid(registerRequestDTO.getPassword())) {
            return Result.fail("密码格式错误，需10~32位字母/数字/下划线!");
        }
        // 邮箱格式校验
        if (RegexUtils.isEmailInvalid(registerRequestDTO.getEmail())) {
            return Result.fail("邮箱格式错误!");
        }
        // 手机号格式校验
        if (RegexUtils.isPhoneInvalid(registerRequestDTO.getPhone())) {
            return Result.fail("手机号格式错误!");
        }

        String username = registerRequestDTO.getUsername().trim();
        String phone = registerRequestDTO.getPhone().trim();
        String email = registerRequestDTO.getEmail().trim();

        // 用户名唯一性校验
        if (userService.lambdaQuery().eq(User::getUsername, username).exists()) {
            return Result.fail("用户名已存在!");
        }
        // 手机号唯一性校验
        if (userService.lambdaQuery().eq(User::getPhone, phone).exists()) {
            return Result.fail("手机号已被注册!");
        }
        // 邮箱唯一性校验
        if (userService.lambdaQuery().eq(User::getEmail, email).exists()) {
            return Result.fail("邮箱已被注册!");
        }

        registerRequestDTO.setUsername(username);
        registerRequestDTO.setPhone(phone);
        registerRequestDTO.setEmail(email);

        // 构建用户对象并加密密码
        User user = BeanUtil.toBean(registerRequestDTO, User.class);
        user.setPassword(PasswordEncoder.encode(registerRequestDTO.getPassword()));
        user.setNickname(registerRequestDTO.getUsername());
        user.setCreateTime(LocalDateTime.now());
        user.setFans(0L);
        user.setVideoCount(0L);
        user.setLikeCount(0L);
        user.setIntroduction("");
        userService.save(user);

        // 发送消息,用于创建/更新用户ES文档
        rocketMQTemplate.syncSend(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(user.getId()));

        log.info("用户 [{}] 注册成功", registerRequestDTO.getUsername());
        return Result.success();
    }

    @Override
    public Result<String> forgetPassword(ForgetPasswordDTO forgetPasswordDTO) {
        // 校验字段
        String newPassword = forgetPasswordDTO.getNewPassword();
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            return Result.fail("密码格式错误，需10~32位字母/数字/下划线!");
        }
        // 校验字段是否有效
        User user = userService.lambdaQuery()
                .eq(User::getUsername, forgetPasswordDTO.getUsername())
                .eq(User::getPhone, forgetPasswordDTO.getPhone())
                .one();
        if (user == null) {
            return Result.fail("用户不存在!");
        }


        //校验通过,把新密码加密后更新数据库
        user.setPassword(PasswordEncoder.encode(newPassword));
        userService.updateById(user);
        log.info("用户 [{}] 重置密码成功", forgetPasswordDTO.getUsername());
        return Result.success();
    }
}
