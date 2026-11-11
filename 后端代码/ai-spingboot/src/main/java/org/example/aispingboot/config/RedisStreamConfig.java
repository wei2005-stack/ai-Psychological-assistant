package org.example.aispingboot.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.aispingboot.mq.AiReplyConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * <h3>Spring Data Redis 3.5 Stream 踩坑记录</h3>
 * <ul>
 *   <li>监听容器只发 XREADGROUP，<b>不会自动建消费者组</b>，组不存在直接 NOGROUP，
 *       所以启动时要先 XGROUP CREATE ... MKSTREAM（见 {@link #ensureConsumerGroup}）；</li>
 *   <li>Spring Data Redis 3.5 的容器<b>不会代替 Listener 发 XACK</b>，必须在消费成功后显式确认，
 *       否则消息永远留在 Pending List（见 AiReplyConsumer）；</li>
 *   <li><b>一旦 XREADGROUP 抛异常，StreamPollTask 会调用 cancel() 直接结束订阅</b>
 *       （javap 反查字节码确认：异常分支先 cancel() 再 handleError()，随后 isSubscriptionActive() 为 false 退出循环）。
 *       所以仅仅在 errorHandler 里"重建消费者组"是不够的——消费线程已经死了。
 *       本类在检测到 NOGROUP 时会<b>重建整个监听容器</b>，让消费自动恢复；</li>
 *   <li>Consumer 名每次启动随机生成，多实例部署时 Redis 会在同组内自动负载均衡。</li>
 * </ul>
 */
@Configuration
/*
 * 注意：这里刻意不使用 @ConditionalOnBean(RedisConnectionFactory.class)。
 *
 * 踩坑记录（E2E 联调时发现消费者一直没启动、AI 回复不落库）：
 *   @ConditionalOnBean 只在「自动配置类」中被可靠地处理；写在普通 @Configuration 上时，
 *   由于配置类解析顺序早于 Redis 自动配置，判定条件的那一刻 RedisConnectionFactory 还不存在，
 *   条件为 false，整个 RedisStreamConfig 被静默跳过，@Bean 监听容器根本没创建，
 *   表现为 Stream 里有消息（XLEN > 0）但消费者组始终为空。
 *
 * 因此改为无条件装配：Redis 不可用时由 AiReplyProducer 侧 catch 异常并降级为同步写库。
 */
public class RedisStreamConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

    /** 当前生效的监听容器（NOGROUP 自愈时会整体重建并替换） */
    private final AtomicReference<StreamMessageListenerContainer<String, MapRecord<String, String, String>>>
            containerRef = new AtomicReference<>();

    /** 防止 NOGROUP 连续触发导致同时重建多个容器 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

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

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                createContainer(factory, aiReplyConsumer);

        containerRef.set(container);
        container.start();
        log.info("Redis Stream 监听容器已启动, Stream={}, Group={}",
                StreamConstants.AI_REPLY_STREAM_KEY,
                StreamConstants.AI_REPLY_CONSUMER_GROUP);
        return container;
    }

    /**
     * 创建一个（尚未 start 的）监听容器，并把订阅注册进去。
     *
     * @param factory  Redis 连接工厂
     * @param consumer 消息处理回调
     * @return 已注册订阅、未启动的容器
     */
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            createContainer(RedisConnectionFactory factory, AiReplyConsumer consumer) {

        ensureConsumerGroup(factory);

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(500))   // 没消息时空轮询间隔，太大增加 Redis 压力
                .batchSize(1)                           // 逐条消费保证顺序；并发落库可加大
                .errorHandler(error -> {
                    if (isNoGroupError(error)) {
                        // Stream/消费者组被清空（Redis 未持久化重启、FLUSHDB、key 被误删等）。
                        // 注意：此时当前消费者线程已经 cancel，必须重建容器才能恢复消费。
                        log.warn("检测到消费者组不存在（NOGROUP），准备重建监听容器");
                        rebuildContainerAsync(factory, consumer);
                    } else {
                        log.error("Redis Stream 监听容器异常", error);
                    }
                })
                .build();

        var container = StreamMessageListenerContainer.create(factory, options);

        container.receive(
                Consumer.from(
                        StreamConstants.AI_REPLY_CONSUMER_GROUP,
                        // 每次启动随机 Consumer 名 → 支持多实例；
                        // 固定 Consumer 名也行（比如 "node-01"），但多实例时需要人工区分
                        "consumer-" + ThreadLocalRandom.current().nextInt(10000)),
                // lastConsumed() 即 XREADGROUP 的 ">"，只读新消息；
                // 建组时用的 ReadOffset.from("0") 保证建组前积压的消息也会被 `>` 投递
                StreamOffset.create(StreamConstants.AI_REPLY_STREAM_KEY, ReadOffset.lastConsumed()),
                consumer);

        return container;
    }

    /**
     * 异步重建监听容器（在独立线程里做，避免在轮询线程栈内 stop/start 造成死锁）。
     *
     * @param factory  Redis 连接工厂
     * @param consumer 消息处理回调
     */
    private void rebuildContainerAsync(RedisConnectionFactory factory, AiReplyConsumer consumer) {
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                // 稍等片刻再重建，让当前出错的轮询线程彻底退出
                Thread.sleep(1000);
                StreamMessageListenerContainer<String, MapRecord<String, String, String>> old =
                        containerRef.getAndSet(null);
                if (old != null) {
                    try {
                        old.stop();
                    } catch (Exception ignored) {
                        // 旧容器已停止，忽略
                    }
                }
                StreamMessageListenerContainer<String, MapRecord<String, String, String>> fresh =
                        createContainer(factory, consumer);
                fresh.start();
                containerRef.set(fresh);
                log.info("Redis Stream 监听容器已重建，消费恢复");
            } catch (Exception e) {
                log.error("重建 Redis Stream 监听容器失败，将等待下次异常重试", e);
            } finally {
                rebuilding.set(false);
            }
        }, "redis-stream-container-rebuild");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 幂等地创建消费者组。
     *
     * <p>{@code ReadOffset.from("0")} 表示「组从 Stream 头部开始投递」：建组瞬间已存在的消息
     * 也会被标记为未投递，随后会被 {@code >} 正常消费，不会漏掉建组前积压的消息。</p>
     *
     * @param factory Redis 连接工厂
     */
    private void ensureConsumerGroup(RedisConnectionFactory factory) {
        try {
            // 用底层连接执行 XGROUP CREATE ... MKSTREAM：
            // mkStream=true 保证「Stream 还不存在」时也能把 Stream + 组一起建出来，
            // 否则全新 Redis 上第一次启动会因 key 不存在而建组失败，消息永远无法投递。
            StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(factory);
            stringRedisTemplate.afterPropertiesSet();
            stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.xGroupCreate(
                        StreamConstants.AI_REPLY_STREAM_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        StreamConstants.AI_REPLY_CONSUMER_GROUP,
                        ReadOffset.from("0"),
                        true);
                return null;
            });
            log.info("已创建 Redis Stream 消费者组: {} / {}",
                    StreamConstants.AI_REPLY_STREAM_KEY, StreamConstants.AI_REPLY_CONSUMER_GROUP);
        } catch (Exception e) {
            // BUSYGROUP（组已存在）或 Redis 暂时不可用，都只记录日志不阻断：
            // 组已存在时后续 XREADGROUP 可直接工作；Redis 不可用时 Producer 会降级同步写库。
            log.info("Redis Stream 消费者组检查跳过（组已存在或 Redis 暂不可用）: {}", e.getMessage());
        }
    }

    /**
     * 判断异常是否为「消费者组/Stream 不存在」。
     * Redis 返回的是 {@code NOGROUP No such key ... or consumer group ...}，
     * 这里按关键字匹配，避免依赖具体的异常类型层级。
     */
    private boolean isNoGroupError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("NOGROUP")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 应用关闭时停掉当前生效的容器（重建过的容器不会被 Spring 自动管理）。 */
    @PreDestroy
    public void shutdown() {
        var container = containerRef.getAndSet(null);
        if (container != null) {
            try {
                container.stop();
            } catch (Exception ignored) {
                // 关闭阶段忽略
            }
        }
    }
}
