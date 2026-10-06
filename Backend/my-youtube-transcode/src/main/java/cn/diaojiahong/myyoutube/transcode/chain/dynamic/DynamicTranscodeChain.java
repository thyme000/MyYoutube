package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

/**
 * 动态成片转码管线在 {@link cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry}
 * 里的分组标识。任务加载/终态检查/CAS 认领不进链, 由监听器自己把关:
 * 认领失败静默返回, 认领之前的异常交给 MQ 重试。
 */
public final class DynamicTranscodeChain {

    public static final String GROUP = "transcode:dynamic";

    private DynamicTranscodeChain() {
    }
}
