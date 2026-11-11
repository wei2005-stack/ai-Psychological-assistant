package org.example.aispingboot.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 连接配置。
 *
 * <p>用 Jackson JSON 替换 RedisTemplate 默认的 JDK 序列化器，
 * 这样 Redis 里存的是可读 JSON，方便直接用 redis-cli 查看调试。</p>
 */
@Configuration
public class RedisConfig {

    /**
     * 构造专供 Redis 使用的 ObjectMapper。
     *
     * <p><b>注意：这里刻意不声明为 {@code @Bean}。</b>
     * 如果把它注册成 Bean，Spring Boot 的 JacksonAutoConfiguration 会因为
     * 「已存在 ObjectMapper」而退避，Spring MVC 的 @RequestBody 反序列化就会复用这个
     * 开启了 defaultTyping 的 mapper，导致所有请求体都必须带 {@code @class} 字段，
     * 前端正常 JSON 会直接解析失败（HttpMessageNotReadableException）。
     * 所以只作为 RedisTemplate 的内部依赖，不进入 Spring 容器。</p>
     *
     * <p>开启 defaultTyping 是为了让 GenericJackson2JsonRedisSerializer 在反序列化时
     * 能还原出具体的 Java 类型，否则取出来全是 LinkedHashMap。</p>
     */
    private ObjectMapper buildRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 允许序列化所有字段（包括 private）
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 开启类型信息嵌入（Redis 里会多一个 @class 字段，用于反序列化还原类型）
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(buildRedisObjectMapper());

        // Key 用字符串序列化（确保 Redis 里看到的是原始字符串）
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 用 Jackson JSON 序列化（确保 Redis 里看到的是可读 JSON）
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
