package cn.diaojiahong.myyoutube.recommend.domain.doc;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ContentDocRepository extends ElasticsearchRepository<ContentDoc, Long> {

    List<ContentDoc> findContentDocByAuthorId(Long authorId);
}
