package cn.diaojiahong.myyoutube.auth.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.CaptchaUtils;
import cn.diaojiahong.myyoutube.common.utils.PasswordEncoder;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import cn.diaojiahong.myyoutube.auth.controller.domain.dto.CaptchaDTO;
import cn.diaojiahong.myyoutube.auth.controller.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.controller.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.controller.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.auth.service.IAuthService;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements IAuthService {

    private final IUserService userService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public Result<String> login(LoginRequestDTO loginRequestDTO) {
        String username = loginRequestDTO.getUsername();
        String rawPassword = loginRequestDTO.getPassword();
        String captchaKey = RedisConstants.CAPTCHA_LOGIN + username;

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

        // 校验验证码（用 Objects.equals 防空指针，验证后立即删除防重用）
        String storedCaptcha = redisTemplate.opsForValue().get(captchaKey);
        if (!Objects.equals(loginRequestDTO.getCaptcha(), storedCaptcha)) {
            return Result.fail("验证码错误或已过期!");
        }
        redisTemplate.delete(captchaKey);

        // 生成 token，将 UserDTO 关键字段以 Hash 形式存入 Redis
        String token = UUID.randomUUID().toString();
        String tokenKey = RedisConstants.USER_TOKEN + token;

        Map<String, String> userMap = new HashMap<>(1);
        userMap.put("id", String.valueOf(user.getId()));
        userMap.put("nickname", user.getNickname());
        redisTemplate.opsForHash().putAll(tokenKey, userMap);
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
        // 校验注册验证码
        String captchaKey = RedisConstants.CAPTCHA_REGISTER + registerRequestDTO.getPhone();
        String storedCaptcha = redisTemplate.opsForValue().get(captchaKey);
        if (storedCaptcha != null && !Objects.equals(registerRequestDTO.getCaptcha(), storedCaptcha)) {
            return Result.fail("验证码错误或已过期!");
        }
        redisTemplate.delete(captchaKey);
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

        // 用户名唯一性校验
        User existUser = userService.lambdaQuery()
                .eq(User::getUsername, registerRequestDTO.getUsername())
                .one();
        if (existUser != null) {
            return Result.fail("用户名已存在!");
        }

        // 构建用户对象并加密密码
        User user = BeanUtil.toBean(registerRequestDTO, User.class);
        user.setPassword(PasswordEncoder.encode(registerRequestDTO.getPassword()));
        user.setNickname(registerRequestDTO.getUsername());

        userService.save(user);
        log.info("用户 [{}] 注册成功", registerRequestDTO.getUsername());
        return Result.success();
    }

    @Override
    public Result<String> captcha(CaptchaDTO captchaDTO) {
        String code = CaptchaUtils.generateNumCaptcha(6);
        if (captchaDTO.getPhone() != null && captchaDTO.getUsername() != null) {
            // 忘记密码验证码，以手机号为 key
            redisTemplate.opsForValue().set(
                    RedisConstants.CAPTCHA_FORGET + captchaDTO.getPhone(),
                    code, RedisConstants.CAPTCHA_EXPIRE, TimeUnit.SECONDS);
            log.info("忘记密码验证码已生成，手机号：{}", captchaDTO.getPhone());
        }
        else if (captchaDTO.getUsername() != null) {
            // 登录验证码，以用户名为 key
            redisTemplate.opsForValue().set(
                    RedisConstants.CAPTCHA_LOGIN + captchaDTO.getUsername(),
                    code, RedisConstants.CAPTCHA_EXPIRE, TimeUnit.SECONDS);
            log.info("登录验证码已生成，用户：{}", captchaDTO.getUsername());
        } else if (captchaDTO.getPhone() != null) {
            // 注册验证码，以手机号为 key
            redisTemplate.opsForValue().set(
                    RedisConstants.CAPTCHA_REGISTER + captchaDTO.getPhone(),
                    code, RedisConstants.CAPTCHA_EXPIRE, TimeUnit.SECONDS);
            log.info("注册验证码已生成，手机号：{}", captchaDTO.getPhone());
        } else {
            return Result.fail("请输入用户名或手机号!");
        }

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

        // 校验验证码
        String captchaKey = RedisConstants.CAPTCHA_FORGET + forgetPasswordDTO.getPhone();
        String storedCaptcha = redisTemplate.opsForValue().get(captchaKey);
        if (storedCaptcha != null && !storedCaptcha.equals(forgetPasswordDTO.getCaptcha())) {
            return Result.fail("验证码错误或已过期!");
        }
        redisTemplate.delete(captchaKey);

        //校验通过,把新密码加密后更新数据库
        user.setPassword(PasswordEncoder.encode(newPassword));
        userService.updateById(user);
        log.info("用户 [{}] 重置密码成功", forgetPasswordDTO.getUsername());
        return Result.success();
    }
}
