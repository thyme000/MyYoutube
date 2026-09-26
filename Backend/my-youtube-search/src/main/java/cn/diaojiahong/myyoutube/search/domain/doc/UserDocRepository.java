package cn.diaojiahong.myyoutube.search.domain.doc;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface UserDocRepository extends ElasticsearchRepository<UserDoc, Long> {

}
