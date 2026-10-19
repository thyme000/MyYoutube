package cn.diaojiahong.myyoutube.common.idempotent;

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
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 执行占位由 IdempotentGuard 管理；成功结果按策略跳过或回放。
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final IdempotentGuard guard;

    @Around("@annotation(cn.diaojiahong.myyoutube.common.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Idempotent idempotent = ((MethodSignature) pjp.getSignature()).getMethod()
                .getAnnotation(Idempotent.class);
        if (idempotent.ttl() < 0) throw new IllegalArgumentException("幂等完成缓存期限不能为负数");
        String key = buildKey(pjp, idempotent);
        try (var lease = guard.acquire(key, idempotent.leaseSeconds())) {
            if (!lease.acquired()) return handleDuplicate(pjp, key, idempotent, lease.cachedValue());
            var previous = guard.enter(lease);
            try {
                Object result = pjp.proceed();
                String value = idempotent.ttl() > 0 && idempotent.policy() == DuplicatePolicy.REPLAY
                        ? JsonUtil.toJson(result) : IdempotentGuard.DONE;
                lease.complete(value, idempotent.ttl());
                return result;
            } finally {
                guard.exit(previous);
            }
        }
    }

    private Object handleDuplicate(ProceedingJoinPoint pjp, String key, Idempotent idempotent, String value) {
        DuplicatePolicy policy = idempotent.policy();
        switch (policy) {
            case SKIP:
                log.info("幂等拦截重复请求: key={}", key);
                return null;
            case REPLAY:
                Method method = ((MethodSignature) pjp.getSignature()).getMethod();
                if (method.getReturnType() == void.class) {
                    return null;
                }
                return JsonUtil.fromJson(value, method.getReturnType());
            case THROW:
            default:
                throw new BizException(BizException.BIZ_ERROR, "请勿重复操作");
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
