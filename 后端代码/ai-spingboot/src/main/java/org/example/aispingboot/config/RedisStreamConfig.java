package org.example.aispingboot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.aispingboot.mq.AiReplyConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis Stream 监听容器配置。
 *
 * <h3>整体架构</h3>
 * <pre>
 *  PsychologicalSupportService.doOnComplete()
 *       │  XADD 消息到 Stream
 *       ▼
 *  ┌─────────────────────────────┐
 *  │   Redis Stream              │  ai:reply:stream
 *  │   (Producer 写入)            │
 *  └─────────────┬───────────────┘
 *                │  XREADGROUP（消费）
 *                ▼
 *  StreamMessageListenerContainer
 *       │  后台轮询 Stream，每 500ms 一次
 *       │  取到消息回调 StreamListener.onMessage()
 *       ▼
 *  AiReplyConsumer.onMessage()
 *       │  解析消息 → 调 ConsultationMessageService.saveAimessage()
 *       ▼
 *  MySQL 落库
 * </pre>
 *
 * <h3>Spring Data Redis 3.5 Stream API 踩坑记录（记下来免得以后再踩）</h3>
 * <ul>
 *   <li>{@code StreamListener} 的泛型签名是 {@code StreamListener<K, V extends Record<K, ?>>}，
 *       不能用 raw type，否则 {@code StreamMessageListenerContainer.create()} 返回的容器类型对不上；</li>
 *   <li>{@code RedisTemplate} 默认 String 序列化，所以 K=String，V=MapRecord 也必须全 String 泛型；
 *       如果想用 byte[] 泛型，要换成 {@code StringRedisTemplate} 或手动指定 Serializer；</li>
 *   <li>{@code Consumer.from(group, name)} 里 name 每次启动生成随机串——
 *       多个 Spring Boot 实例（比如负载均衡后）时，Consumer Group 下会有多个动态命名 Consumer，
 *       Redis 自动做负载均衡，每条消息只发给一个 Consumer；</li>
 *   <li>{@code ReadOffset.lastConsumed()} —— 监听容器从上次消费到的位置继续读，
 *       新实例启动从末尾开始（不补历史积压），要补历史用 {@code ReadOffset.from("0")}。</li>
 * </ul>
 */
@Configuration
// 只有 RedisConnectionFactory 存在（即 Redis ChatMemory 可用时）才创建这个配置，
// Redis 没连上时整个 Stream 链路都跳过，Producer 侧 catch 异常后降级为同步写库。
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisStreamConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

    /**
     * 创建并启动 Stream 监听容器。
     *
     * @param factory          Redis 连接工厂（LettuceConnectionFactory）
     * @param aiReplyConsumer  消息处理回调，由 Spring 自动注入
     * @return 启动好的监听容器（Spring 会在 Context 关闭时自动 stop）
     */
    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            aiReplyStreamContainer(RedisConnectionFactory factory,
                                   AiReplyConsumer aiReplyConsumer) {

        // ---- 容器选项 ----
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(500))   // 没消息时空轮询间隔，太大会增加 Redis 压力
                .batchSize(1)                           // 逐条消费，保证顺序；并发落库可加大
                .build();

        var container = StreamMessageListenerContainer.create(factory, options);

        // ---- 订阅 ----
        // 第一个实例启动时 Spring Data Redis 会自动：
        //   1) XGROUP CREATE ai-reply-consumers MKSTREAM（不存在 Stream 时一并创建）
        //   2) XREADGROUP GROUP ai-reply-consumers consumer-XXXX COUNT 1 STREAMS ai:reply:stream $
        container.receive(
                Consumer.from(
                        StreamConstants.AI_REPLY_CONSUMER_GROUP,
                        // 每次启动随机 Consumer 名 → 支持多实例；
                        // 固定 Consumer 名也行（比如 "node-01"），但多实例时需要人工区分
                        "consumer-" + ThreadLocalRandom.current().nextInt(10000)),
                // lastConsumed → 从上次消费到的位置继续；
                // 新实例第一次订阅相当于 "$"（最新位置），不会补消费之前积压的消息
                StreamOffset.create(StreamConstants.AI_REPLY_STREAM_KEY, ReadOffset.lastConsumed()),
                aiReplyConsumer);

        container.start();
        log.info("Redis Stream 监听容器已启动, Stream={}, Group={}",
                StreamConstants.AI_REPLY_STREAM_KEY,
                StreamConstants.AI_REPLY_CONSUMER_GROUP);
        return container;
    }
}
