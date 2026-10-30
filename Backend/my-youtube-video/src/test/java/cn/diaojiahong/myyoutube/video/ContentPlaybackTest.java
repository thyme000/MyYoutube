package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPlaybackDTO;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentPlaybackVO;
import cn.diaojiahong.myyoutube.content.mapper.ContentPlaybackMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentPlaybackTest {
    final ContentAccessService access = mock(ContentAccessService.class);

    final ContentTypeStrategy strategy = mock(ContentTypeStrategy.class);

    final long started = System.currentTimeMillis() - 20_000;
    DriverManagerDataSource ds;
    SqlSessionFactory factory;
    ContentPlaybackService service;
    org.apache.ibatis.session.SqlSession session;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        ds = ContentTestDatabase.create("playback");
        jdbc = new JdbcTemplate(ds);
        ContentTestDatabase.drop(jdbc,"content_playback");
        jdbc.execute("CREATE TABLE content_playback(user_id BIGINT, content_id BIGINT, session_id VARCHAR(64),session_started_at BIGINT,sequence BIGINT,watched_ms BIGINT,video_position_ms BIGINT,image_index INT,image_elapsed_ms BIGINT,audio_position_ms BIGINT,PRIMARY KEY(user_id,content_id))");
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setMapperLocations(new ClassPathResource("mapper/ContentPlaybackMapper.xml"));
        factory = bean.getObject();
        session = factory.openSession(true);
        dynamic(true);
    }

    @AfterEach
    void close() {
        session.close();
    }

    void dynamic(boolean audio) {
        when(strategy.type()).thenReturn(ContentTypeEnum.DYNAMIC);
        Content content = new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC);
        when(access.locked(1L)).thenReturn(content);
        when(access.requireReadable(content,9L)).thenReturn(content);
        when(strategy.loadMedia(List.of(1L))).thenReturn(Map.of(1L,new ContentMedia("dynamic",null,
                new DynamicMedia("vertical",1080,1920,"fade",800,List.of(new DynamicMedia.Image("a",0,3000,.5,.5),
                        new DynamicMedia.Image("b",2200,3000,.5,.5)),audio ? new DynamicMedia.Audio(2L,
                        "music",
                        "音乐",
                        2000) : null,5200))));
        service = new ContentPlaybackService(
                access,
                new ContentTypeStrategyRegistry(List.of(strategy)),
                session.getMapper(ContentPlaybackMapper.class));
    }

    ContentPlaybackDTO state(long seq,long watched,int image,long elapsed,Long audio) {
        return new ContentPlaybackDTO("session",started,seq,watched,null,image,elapsed,audio);
    }

    @Test
    void recordsIndependentPositionsAndActualWatchTime() {
        service.save(1L,9L,state(1,400,1,80,1500L));
        ContentPlaybackVO result=service.get(1L,9L);
        assertNull(result.videoPositionMs());
        assertEquals(1,result.imageIndex());
        assertEquals(80L,result.imageElapsedMs());
        assertEquals(1500L,result.audioPositionMs());
        assertEquals(400L,jdbc.queryForObject("SELECT watched_ms FROM content_playback",Long.class));
        assertNull(service.get(1L,10L));
    }

    @Test
    void olderFlushAndPreviousSessionCannotOverwriteNewerState() {
        service.save(1L,9L,state(2,5000,1,1000,100L));
        service.save(1L,9L,state(1,4000,0,50,1500L));
        service.save(1L,9L,new ContentPlaybackDTO("older",started-1000,99L,4000L,null,0,40L,1800L));
        ContentPlaybackVO result=service.get(1L,9L);
        assertEquals(1,result.imageIndex());
        assertEquals(1000L,result.imageElapsedMs());
        assertEquals(100L,result.audioPositionMs());
        service.save(1L,9L,new ContentPlaybackDTO("newer",started+1000,1L,20L,null,0,20L,0L));
        assertEquals(0,service.get(1L,9L).imageIndex());
    }

    @Test
    void invalidIndexesTimesAndMixedMediaAreRejected() {
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,0,2,0,0L)));
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,0,0,3000,0L)));
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,0,0,-1,0L)));
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,0,0,0,2001L)));
        assertThrows(BizException.class,()->service.save(1L,9L,new ContentPlaybackDTO("s",started,1L,0L,100L,0,0L,0L)));
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,Long.MAX_VALUE,0,0,0L)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_playback",Integer.class));
    }

    @Test
    void silentImagesRequireNoAudioPosition() {
        dynamic(false);
        service.save(1L,9L,state(1,0,0,0,null));
        assertNull(service.get(1L,9L).audioPositionMs());
        assertThrows(BizException.class,()->service.save(1L,9L,state(2,10,0,10,0L)));
    }

    @Test
    void videoUsesOnlyVideoPosition() {
        when(strategy.type()).thenReturn(ContentTypeEnum.VIDEO);
        Content content=new Content().setId(1L).setType(ContentTypeEnum.VIDEO);
        when(access.locked(1L)).thenReturn(content);
        when(access.requireReadable(content,9L)).thenReturn(content);
        when(strategy.loadMedia(List.of(1L))).thenReturn(Map.of(
                1L,
                new ContentMedia("video",new VideoMedia("v",10000),null)));
        service=new ContentPlaybackService(
                access,
                new ContentTypeStrategyRegistry(List.of(strategy)),
                session.getMapper(ContentPlaybackMapper.class));
        service.save(1L,9L,new ContentPlaybackDTO("s",started,1L,500L,9000L,null,null,null));
        assertEquals(9000L,service.get(1L,9L).videoPositionMs());
        assertThrows(BizException.class,()->service.save(1L,9L,state(2,500,0,0,null)));
    }

    @Test
    void currentPermissionCheckedBeforeReadingOrWritingResume() {
        when(access.readable(1L,9L)).thenThrow(new BizException(ErrorCode.FORBIDDEN, "不可访问"));
        assertThrows(BizException.class,()->service.get(1L,9L));
        when(access.requireReadable(any(),eq(9L))).thenThrow(new BizException(ErrorCode.FORBIDDEN, "不可访问"));
        assertThrows(BizException.class,()->service.save(1L,9L,state(1,0,0,0,0L)));
        verify(strategy,never()).loadMedia(any());
    }
}
