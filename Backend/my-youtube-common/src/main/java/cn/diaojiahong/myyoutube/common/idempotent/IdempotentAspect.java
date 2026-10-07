package cn.diaojiahong.myyoutube.common.idempotent;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 占位值三态: RUNNING=执行中, DONE=已成功(无回放), 其余=上次成功结果的 JSON(REPLAY)。
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String RUNNING = "RUNNING";
    private static final String DONE = "DONE";
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final StringRedisTemplate redis;

    @Around("@annotation(cn.diaojiahong.myyoutube.common.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Idempotent idempotent = ((MethodSignature) pjp.getSignature()).getMethod()
                .getAnnotation(Idempotent.class);
        String key = RedisConstants.IDEMPOTENT + buildKey(pjp, idempotent);
        Duration ttl = Duration.ofSeconds(idempotent.ttl());

        Boolean acquired = redis.opsForValue().setIfAbsent(key, RUNNING, ttl);
        if (acquired == null || !acquired) {
            return handleDuplicate(pjp, key, idempotent);
        }
        try {
            Object result = pjp.proceed();
            markDone(key, idempotent, result, ttl);
            return result;
        } catch (Throwable e) {
            try {
                redis.delete(key);
            } catch (RuntimeException ex) {
                log.error("幂等占位释放失败, TTL 内会误拦重试: key={}", key, ex);
            }
            throw e;
        }
    }

    private Object handleDuplicate(ProceedingJoinPoint pjp, String key, Idempotent idempotent) {
        String value = redis.opsForValue().get(key);
        DuplicatePolicy policy = idempotent.policy();
        switch (policy) {
            case SKIP:
                log.info("幂等拦截重复请求: key={}", key);
                return null;
            case REPLAY:
                if (value == null || RUNNING.equals(value)) {
                    throw new BizException(BizException.BIZ_ERROR, "操作正在处理中,请稍候");
                }
                Method method = ((MethodSignature) pjp.getSignature()).getMethod();
                if (method.getReturnType() == void.class) {
                    return null;
                }
                return JsonUtil.fromJson(value, method.getReturnType());
            case THROW:
            default:
                boolean running = value == null || RUNNING.equals(value);
                throw new BizException(BizException.BIZ_ERROR,
                        running ? "操作正在处理中,请稍候" : "请勿重复操作");
        }
    }

    private void markDone(String key, Idempotent idempotent, Object result, Duration ttl) {
        String value = idempotent.policy() == DuplicatePolicy.REPLAY ? JsonUtil.toJson(result) : DONE;
        try {
            Long remaining = redis.getExpire(key);
            long seconds = (remaining == null || remaining < 0) ? ttl.getSeconds() : remaining;
            redis.opsForValue().set(key, value, Duration.ofSeconds(seconds));
        } catch (RuntimeException e) {
            log.error("幂等完成标记写入失败, TTL 内重复请求会被误报处理中: key={}", key, e);
        }
    }

    private String buildKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(
                null, method, pjp.getArgs(), new DefaultParameterNameDiscoverer());
        String userId = UserContext.getUserId();
        if (userId != null) {
            ctx.setVariable("userId", userId);
        }
        String key = PARSER.parseExpression(idempotent.key()).getValue(ctx, String.class);
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("幂等 key 解析为空, 拒绝执行以防全量误判: " + idempotent.key());
        }
        return key;
    }
}
