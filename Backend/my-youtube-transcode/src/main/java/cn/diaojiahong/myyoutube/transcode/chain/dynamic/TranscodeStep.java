package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.common.chain.SyncChainStep;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 动态转码管线步骤基类: 收拢分组与排序, 并把子类的受检 IO 异常桥接成非受检 ——
 * 任一步失败由监听器统一落失败终态 + SSE 通知 + 清理临时目录。
 */
public abstract class TranscodeStep implements SyncChainStep<TranscodeContext> {

    private final int order;

    protected TranscodeStep(int order) {
        this.order = order;
    }

    @Override
    public final void apply(TranscodeContext ctx) {
        try {
            doApply(ctx);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected abstract void doApply(TranscodeContext ctx) throws Exception;

    @Override
    public final String group() {
        return DynamicTranscodeChain.GROUP;
    }

    @Override
    public final int order() {
        return order;
    }
}
