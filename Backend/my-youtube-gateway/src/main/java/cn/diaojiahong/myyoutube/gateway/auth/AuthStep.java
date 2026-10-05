package cn.diaojiahong.myyoutube.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * 网关鉴权责任链的单步。实现按 @Order 排序依次执行:
 * 与 ctx 无关的步骤直接返回 Mono.empty() 交给下一步;
 * 接手的步骤异步完成后写回 ctx 决策(skip/authenticated/reject), 编排器见决策即短路。
 * 将来加第三种凭证 = 新增一个实现类 + 一个 @Order, 过滤器与既有步骤零改动。
 */
public interface AuthStep {

    Mono<Void> apply(AuthContext ctx);
}
