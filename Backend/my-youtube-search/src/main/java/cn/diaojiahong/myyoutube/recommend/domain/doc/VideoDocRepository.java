package cn.diaojiahong.myyoutube.recommend.domain.doc;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface VideoDocRepository extends ElasticsearchRepository<VideoDoc, Long> {

    List<VideoDoc> findVideoDocByAuthorId(Long authorId);
}
