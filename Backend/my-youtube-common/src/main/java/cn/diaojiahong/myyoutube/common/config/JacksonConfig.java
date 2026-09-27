package cn.diaojiahong.myyoutube.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一 JSON 表现：Long → 字符串；LocalDateTime → yyyy-MM-dd HH:mm:ss
 */
@Configuration
public class JacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            // Long / long 序列化为字符串；反序列化兼容数字和字符串
            SimpleModule longModule = new SimpleModule("LongAsString");
            longModule.addSerializer(Long.class, ToStringSerializer.instance);
            longModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
            longModule.addDeserializer(Long.class, new LongFlexibleDeserializer());
            longModule.addDeserializer(Long.TYPE, new LongPrimitiveFlexibleDeserializer());

            // LocalDateTime → yyyy-MM-dd HH:mm:ss
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(fmt));
            javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(fmt));
            builder.modules(longModule, javaTimeModule);
            // Date 类型也统一格式
            builder.dateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
            // 不要把日期写成时间戳数组
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            builder.modules(longModule, javaTimeModule);

            // Date 类型也统一格式
            builder.dateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
            // 不要把日期写成时间戳数组
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }
    /** 可空 Long：支持 JSON 数字或字符串 */
    static class LongFlexibleDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return switch (p.currentToken()) {
                case VALUE_NUMBER_INT -> p.getLongValue();
                case VALUE_STRING -> {
                    String s = p.getText();
                    yield (s == null || s.isBlank()) ? null : Long.parseLong(s.trim());
                }
                case VALUE_NULL -> null;
                default -> (Long) ctxt.handleUnexpectedToken(Long.class, p);
            };
        }
    }
    /** 基本类型 long：不支持 null，缺省用 0 或抛错可按需改 */
    static class LongPrimitiveFlexibleDeserializer extends JsonDeserializer<Long> {
        private final JsonDeserializer<?> delegate =
                new NumberDeserializers.LongDeserializer(Long.TYPE, 0L);
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING) {
                String s = p.getText();
                if (s == null || s.isBlank()) {
                    throw new IllegalArgumentException("long 类型不能为空");
                }
                return Long.parseLong(s.trim());
            }
            return (Long) delegate.deserialize(p, ctxt);
        }
    }
}
