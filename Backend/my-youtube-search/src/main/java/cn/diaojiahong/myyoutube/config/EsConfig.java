package cn.diaojiahong.myyoutube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

@Configuration
public class EsConfig {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @WritingConverter
    public enum LocalDateTimeToStringConverter implements Converter<LocalDateTime, String> {
        INSTANCE;

        @Override
        public String convert(LocalDateTime source) {
            return source.format(FMT);
        }
    }

    @ReadingConverter
    public enum StringToLocalDateTimeConverter implements Converter<String, LocalDateTime> {
        INSTANCE;

        @Override
        public LocalDateTime convert(String source) {
            return LocalDateTime.parse(source, FMT);
        }
    }

    @Bean
    public ElasticsearchCustomConversions elasticsearchCustomConversions() {
        return new ElasticsearchCustomConversions(Arrays.asList(
                LocalDateTimeToStringConverter.INSTANCE,
                StringToLocalDateTimeConverter.INSTANCE
        ));
    }
}
