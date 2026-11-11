package org.example.aispingboot.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.aispingboot.config.StreamConstants;
import org.example.aispingboot.service.ConsultationMessageService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 回复消息生产者（Redis Stream —— XADD 端）。
 *
 * <h3>为什么用 Redis Stream 而不是 RabbitMQ / RocketMQ</h3>
 * <ul>
 *   <li>项目已经接了 Redis（ChatMemory 持久化用），Stream 是 Redis 自带功能，零额外依赖；</li>
 *   <li>消息量不大（每轮 AI 对话才一条消息），Redis Stream 性能完全够用；</li>
 *   <li>Redis Stream 提供 Consumer Group + Pending List，
 *       消费者挂掉后消息自动保留，重平衡时重新领取，不丢不重。</li>
 * </ul>
 *
 * <h3>降级策略（关键设计）</h3>
 * <pre>
 *  try:
 *      redisTemplate.opsForStream().add(key, fields)   ← XADD 微秒级
 *  catch Redis 不可用:
 *      降级同步调 saveAimessage(dbSessionId, content, aiModel)
 *      保证数据不丢，只是 SSE 收尾时间回到原来的同步水平
 *  catch 同步也挂了 (MySQL 挂了):
 *      log.error 记完整 sessionId + 前 80 字回复
 *      （极端场景，需要人工介入；实际线上概率极低）
 * </pre>
 *
 * <h3>调用位置</h3>
 * 由 {@code PsychologicalSupportService#chatStreamed} 的 {@code doOnComplete} 回调调用，
 * 原来的代码是直接 {@code consultationMessageService.saveAimessage(...)}，
 * 改为 {@code aiReplyProducer.publish(...)} 后立即 {@code sink.complete()}，
 * SSE 连接不再等待 MySQL 写入。
 *
 * @see StreamConstants 常量集中维护
 * @see AiReplyConsumer  消费端
 */
@Component
public class AiReplyProducer {

    private static final Logger log = LoggerFactory.getLogger(AiReplyProducer.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConsultationMessageService consultationMessageService;

    /**
     * 构造注入。
     *
     * <p><b>为什么用 StringRedisTemplate 而不是全局的 RedisTemplate&lt;String, Object&gt;？</b>
     * 全局 RedisTemplate 的 value 序列化器是「GenericJackson2JsonRedisSerializer + defaultTyping」，
     * 写 Stream 时每个字段值会被序列化成带引号的 JSON 字符串（如 {@code "23"}），
     * 消费端用 String 序列化器读出来就是带引号的 {@code "23"}，
     * {@code Long.parseLong("23".length==4)} 会直接抛 NumberFormatException，消息永远消费失败。
     * StringRedisTemplate 原样存取，Producer / Consumer 两端字节完全一致。</p>
     *
     * @param stringRedisTemplate         字符串 Redis 客户端（Spring Boot 自动配置）
     * @param consultationMessageService  MySQL 落库服务，Redis 不可用时降级直接调用
     */
    public AiReplyProducer(StringRedisTemplate stringRedisTemplate,
                           ConsultationMessageService consultationMessageService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.consultationMessageService = consultationMessageService;
    }

    /**
     * 发布一条 AI 回复消息到 Redis Stream。
     *
     * <p>字段序列化为 String 存入 Stream（Spring Boot 默认 StringRedisSerializer），
     * Consumer 端按同名字段读取。</p>
     *
     * @param dbSessionId 数据库会话表主键，标识"哪一轮对话"
     * @param content     AI 完整回复（可能数百到上千字）
     * @param aiModel     模型标识，当前固定 "openai"（对应 Spring AI OpenAiChatModel）
     */
    public void publish(Long dbSessionId, String content, String aiModel) {
        Map<String, String> fields = new HashMap<>(4);
        fields.put(StreamConstants.FIELD_DB_SESSION_ID, String.valueOf(dbSessionId));
        fields.put(StreamConstants.FIELD_CONTENT, content);
        fields.put(StreamConstants.FIELD_AI_MODEL, aiModel);

        try {
            // RedisTemplate.opsForStream().add() 底层是 XADD 命令，返回自动生成的 Stream ID
            // （形如 "1794323420684-0"），Producer 端不关心 ID，Redis 自己维护
            stringRedisTemplate.opsForStream()
                    .add(StreamConstants.AI_REPLY_STREAM_KEY, fields);
            // DEBUG 级别避免生产环境刷屏，关键路径日志用 INFO/WARN/ERROR
            log.debug("AI 回复已发布到 Redis Stream, sessionId={}", dbSessionId);
        } catch (Exception e) {
            // ---- Redis 不可用降级 ----
            // 能走到这里的异常：Redis 连不上（Connection refused）、
            //  Stream 写超时、Lettuce 连接池耗尽等
            log.warn("Redis Stream 发布失败，降级为同步写库。sessionId={}, 原因: {}",
                    dbSessionId, e.getMessage());

            try {
                // 直接调 MySQL 落库（原来的同步逻辑），保证数据不丢
                consultationMessageService.saveAimessage(dbSessionId, content, aiModel);
                log.info("降级同步写库成功, sessionId={}", dbSessionId);
            } catch (Exception saveEx) {
                // ---- MySQL 也挂了（极端场景）----
                // 只能记 ERROR 日志，把关键字段打出来方便排查
                log.error("降级同步写库也失败！sessionId={}, 回复前 80 字: {}",
                        dbSessionId,
                        content.length() > 80 ? content.substring(0, 80) + "..." : content,
                        saveEx);
            }
        }
    }
}
