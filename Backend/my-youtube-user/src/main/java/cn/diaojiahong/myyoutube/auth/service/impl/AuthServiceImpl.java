package cn.diaojiahong.myyoutube.auth.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.auth.chain.register.RegisterChain;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
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
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
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
    private final EsSyncPublisher esSyncPublisher;
    private final SyncChainStepRegistry chainRegistry;

    /** 续签轮换原子化: 同一 refresh 并发只有一个请求能成功 */
    private static final RedisScript<String> REFRESH_ROTATE_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/refresh-rotate.lua"), String.class);

    @Override
    @ObservedBusiness(BusinessOperation.LOGIN)
    public LoginResponseDTO login(LoginRequestDTO loginRequestDTO) {
        String username = loginRequestDTO.getUsername();
        String rawPassword = loginRequestDTO.getPassword();

        // 查询用户
        User user = userService.lambdaQuery()
                .eq(User::getUsername, username)
                .one();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在!");
        }

        // BCrypt 校验：matches(明文, 哈希)，第一个参数必须是原始明文
        if (!PasswordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "密码错误!");
        }


        // access JWT 短期有效; refresh UUID 存 Redis 供续签/登出/轮换
        String accessToken = JwtUtil.sign(user.getId());
        String refreshToken = UUID.randomUUID().toString();
        saveRefreshToken(refreshToken, user.getId());

        log.info("用户 [{}] 登录成功 userId={}", username, user.getId());
        return new LoginResponseDTO(accessToken, refreshToken);
    }

    @Override
    @ObservedBusiness(BusinessOperation.REFRESH)
    public LoginResponseDTO refresh(RefreshTokenDTO refreshTokenDTO) {
        String refreshToken = refreshTokenDTO == null ? null : refreshTokenDTO.getRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录!");
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
            // USED 意味着同一刷新凭证被二次使用, 属可关注的安全信号; 其余是正常过期
            if ("USED".equals(userId)) log.warn("刷新凭证被重复使用, 已按失效处理");
            else log.debug("刷新凭证无效 reason={}", userId == null ? "NOT_FOUND_OR_EXPIRED" : "REVOKED_BY_TOKEN_VERSION");
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录!");
        }

        log.debug("刷新凭证轮换成功 userId={}", userId);
        return new LoginResponseDTO(JwtUtil.sign(Long.parseLong(userId)), newRefreshToken);
    }

    @Override
    @ObservedBusiness(BusinessOperation.LOGOUT)
    public void logout(RefreshTokenDTO refreshTokenDTO) {
        String refreshToken = refreshTokenDTO == null ? null : refreshTokenDTO.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            redisTemplate.delete(RedisConstants.USER_REFRESH_TOKEN + refreshToken);
            log.debug("登出已删除刷新凭证");
        }
        return;
    }

    private void saveRefreshToken(String refreshToken, Long userId) {
        String key = RedisConstants.USER_REFRESH_TOKEN + refreshToken;
        // set 带 TTL 一步到位, 先 set 后 expire 中间崩溃会留下永不过期的凭证
        redisTemplate.opsForValue().set(key, userId + ":" + System.currentTimeMillis(),
                RedisConstants.USER_REFRESH_TOKEN_EXPIRE, TimeUnit.HOURS);
    }

    @Override
    @ObservedBusiness(BusinessOperation.REGISTER)
    public void register(RegisterRequestDTO registerRequestDTO) {
        // 格式/规范化/唯一性校验全部在链上, 步骤见 auth.chain.register
        chainRegistry.execute(RegisterChain.GROUP, registerRequestDTO);

        // 构建用户对象并加密密码
        User user = BeanUtil.toBean(registerRequestDTO, User.class);
        user.setPassword(PasswordEncoder.encode(registerRequestDTO.getPassword()));
        user.setNickname(registerRequestDTO.getUsername());
        user.setCreateTime(LocalDateTime.now());
        user.setFans(0L);
        user.setContentCount(0L);
        user.setLikeCount(0L);
        user.setIntroduction("");
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞 uk_username/uk_phone/uk_email
            throw new BizException(ErrorCode.STATE_CONFLICT, "用户名或手机号或邮箱已被注册!");
        }

        // 发送消息,用于创建/更新用户ES文档(延迟 5s, 短时间多次变更消费端合并)
        esSyncPublisher.syncUserDoc(user.getId());

        log.info("用户 [{}] 注册成功 userId={}", registerRequestDTO.getUsername(), user.getId());
        return;
    }

    @Override
    @ObservedBusiness(BusinessOperation.RESET_PASSWORD)
    public void forgetPassword(ForgetPasswordDTO forgetPasswordDTO) {
        // 校验字段
        String newPassword = forgetPasswordDTO.getNewPassword();
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "密码格式错误，需10~32位字母/数字/下划线!");
        }
        // 校验字段是否有效
        User user = userService.lambdaQuery()
                .eq(User::getUsername, forgetPasswordDTO.getUsername())
                .eq(User::getPhone, forgetPasswordDTO.getPhone())
                .one();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在!");
        }


        //校验通过,把新密码加密后更新数据库
        user.setPassword(PasswordEncoder.encode(newPassword));
        userService.updateById(user);

        // 作废该用户改密码前签发的所有 access/refresh
        // TTL 取 refresh 的 7d: 改密前签发的 token 最长活 7d, 过后 token-ver 本身也没有存在意义
        redisTemplate.opsForValue().set(
                RedisConstants.USER_TOKEN_VER + user.getId(),
                String.valueOf(System.currentTimeMillis()),
                RedisConstants.USER_REFRESH_TOKEN_EXPIRE, TimeUnit.HOURS);

        log.info("用户 [{}] 重置密码成功 userId={}, 已签发凭证全部作废", forgetPasswordDTO.getUsername(), user.getId());
        return;
    }
}
