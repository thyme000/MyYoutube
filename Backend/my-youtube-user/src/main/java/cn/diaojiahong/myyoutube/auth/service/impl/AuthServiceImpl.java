package cn.diaojiahong.myyoutube.auth.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.PasswordEncoder;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginResponseDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RefreshTokenDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import cn.diaojiahong.myyoutube.auth.service.IAuthService;
import cn.diaojiahong.myyoutube.search.api.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {

    private final IUserService userService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    /** 续签轮换原子化: 同一 refresh 并发只有一个请求能成功 */
    private static final RedisScript<String> REFRESH_ROTATE_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/refresh-rotate.lua"), String.class);

    @Override
    public LoginResponseDTO login(LoginRequestDTO loginRequestDTO) {
        String username = loginRequestDTO.getUsername();
        String rawPassword = loginRequestDTO.getPassword();

        // 查询用户
        User user = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .one();
        if (user == null) {
            throw new BizException(BizException.BIZ_ERROR, "用户不存在!");
        }

        // BCrypt 校验：matches(明文, 哈希)，第一个参数必须是原始明文
        if (!PasswordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException(BizException.BIZ_ERROR, "密码错误!");
        }


        // access JWT 短期有效; refresh UUID 存 Redis 供续签/登出/轮换
        String accessToken = JwtUtil.sign(user.getId());
        String refreshToken = UUID.randomUUID().toString();
        saveRefreshToken(refreshToken, user.getId());

        log.info("用户 [{}] 登录成功", username);
        return new LoginResponseDTO(accessToken, refreshToken);
    }

    @Override
    public LoginResponseDTO refresh(RefreshTokenDTO refreshTokenDTO) {
        String refreshToken = refreshTokenDTO == null ? null : refreshTokenDTO.getRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(BizException.BIZ_ERROR, "登录已过期，请重新登录!");
        }

        String newRefreshToken = UUID.randomUUID().toString();
        String userId = redisTemplate.execute(
                REFRESH_ROTATE_SCRIPT,
                List.of(
                        RedisConstants.USER_REFRESH_TOKEN + refreshToken,
                        RedisConstants.USER_REFRESH_TOKEN + newRefreshToken),
                RedisConstants.USER_TOKEN_VER,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(RedisConstants.USER_REFRESH_TOKEN_EXPIRE * 3600));

        // USED=已用过/不存在; REVOKED=被 token-ver 作废(改密码/封号)
        if (userId == null || userId.equals("USED") || userId.equals("REVOKED")) {
            throw new BizException(BizException.BIZ_ERROR, "登录已过期，请重新登录!");
        }

        return new LoginResponseDTO(JwtUtil.sign(Long.parseLong(userId)), newRefreshToken);
    }

    @Override
    public void logout(RefreshTokenDTO refreshTokenDTO) {
        String refreshToken = refreshTokenDTO == null ? null : refreshTokenDTO.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            redisTemplate.delete(RedisConstants.USER_REFRESH_TOKEN + refreshToken);
        }
        return;
    }

    private void saveRefreshToken(String refreshToken, Long userId) {
        String key = RedisConstants.USER_REFRESH_TOKEN + refreshToken;
        redisTemplate.opsForValue().set(key, userId + ":" + System.currentTimeMillis());
        redisTemplate.expire(key, RedisConstants.USER_REFRESH_TOKEN_EXPIRE, TimeUnit.HOURS);
    }

    @Override
    public void register(RegisterRequestDTO registerRequestDTO) {
        // 两次密码一致性校验
        if (!registerRequestDTO.getPassword().equals(registerRequestDTO.getConfirmPassword())) {
            throw new BizException(BizException.BIZ_ERROR, "两次密码不一致!");
        }


        // 密码格式校验
        if (RegexUtils.isPasswordInvalid(registerRequestDTO.getPassword())) {
            throw new BizException(BizException.BIZ_ERROR, "密码格式错误，需10~32位字母/数字/下划线!");
        }
        // 邮箱格式校验
        if (RegexUtils.isEmailInvalid(registerRequestDTO.getEmail())) {
            throw new BizException(BizException.BIZ_ERROR, "邮箱格式错误!");
        }
        // 手机号格式校验
        if (RegexUtils.isPhoneInvalid(registerRequestDTO.getPhone())) {
            throw new BizException(BizException.BIZ_ERROR, "手机号格式错误!");
        }

        String username = registerRequestDTO.getUsername().trim();
        String phone = registerRequestDTO.getPhone().trim();
        String email = registerRequestDTO.getEmail().trim();

        // 用户名唯一性校验
        if (userService.lambdaQuery().eq(User::getUsername, username).exists()) {
            throw new BizException(BizException.BIZ_ERROR, "用户名已存在!");
        }
        // 手机号唯一性校验
        if (userService.lambdaQuery().eq(User::getPhone, phone).exists()) {
            throw new BizException(BizException.BIZ_ERROR, "手机号已被注册!");
        }
        // 邮箱唯一性校验
        if (userService.lambdaQuery().eq(User::getEmail, email).exists()) {
            throw new BizException(BizException.BIZ_ERROR, "邮箱已被注册!");
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
        return;
    }

    @Override
    public void forgetPassword(ForgetPasswordDTO forgetPasswordDTO) {
        // 校验字段
        String newPassword = forgetPasswordDTO.getNewPassword();
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            throw new BizException(BizException.BIZ_ERROR, "密码格式错误，需10~32位字母/数字/下划线!");
        }
        // 校验字段是否有效
        User user = userService.lambdaQuery()
                .eq(User::getUsername, forgetPasswordDTO.getUsername())
                .eq(User::getPhone, forgetPasswordDTO.getPhone())
                .one();
        if (user == null) {
            throw new BizException(BizException.BIZ_ERROR, "用户不存在!");
        }


        //校验通过,把新密码加密后更新数据库
        user.setPassword(PasswordEncoder.encode(newPassword));
        userService.updateById(user);

        // 作废该用户改密码前签发的所有 access/refresh
        redisTemplate.opsForValue().set(
                RedisConstants.USER_TOKEN_VER + user.getId(),
                String.valueOf(System.currentTimeMillis()));

        log.info("用户 [{}] 重置密码成功", forgetPasswordDTO.getUsername());
        return;
    }
}
