package cn.diaojiahong.myyoutube.llm.embedding;

import java.util.List;

/**
 * 向量化策略: 一个实现对应一类厂商协议; 新增厂商 = 新增实现, 编排层(EmbeddingOrchestrator)不动
 */
public interface EmbeddingProvider {

    /**
     * @param texts 批量文本(数量不超过 batch-size), 顺序与返回向量一一对应
     * @return 向量列表
     */
    List<float[]> embed(List<String> texts);
}
