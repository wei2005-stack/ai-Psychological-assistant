package org.example.aispingboot.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.aispingboot.config.StreamConstants;
import org.example.aispingboot.service.ConsultationMessageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * AI 回复消息消费者（Redis Stream —— XREADGROUP 端）。
 *
 * <h3>消费流程（StreamListener 接口）</h3>
 * <pre>
 * Redis Stream 有新消息 ──▶ ListenerContainer 轮询到
 *                                     │
 *                                     ▼
 *                          AiReplyConsumer.onMessage(record)
 *                                     │
 *                                     ▼
 *                          解析 fields → 调 saveAimessage()
 *                                     │
 *                          成功 ───── 抛异常 ───── 失败
 *                            │                      │
 *                      ListenerContainer         ListenerContainer 不 ack
 *                      自动 ACK ✅              消息留在 Pending List
 *                                                     │
 *                                                     ▼
 *                                          下次重平衡或 XCLAIM 后
 *                                          被其他 Consumer 重新领取
 * </pre>
 *
 * <h3>泛型选择说明</h3>
 * Spring Boot 默认 {@code RedisTemplate} 使用 {@code StringRedisSerializer}，
 * 所以 K=String，V=MapRecord 三个泛型都是 String。
 * 如果换成 byte[] 泛型，需要手动换 Serializer 或用底层 {@code RedisConnection}，
 * 没必要——这里 field/value 都是可打印字符串。
 *
 * <h3>ACK 机制（Spring Data Redis 帮你处理了）</h3>
 * ListenerContainer 在 {@code onMessage} 正常返回时会自动 XACK；
 * 如果 {@code onMessage} 抛出异常，容器不会 ACK，消息留在 Pending Entries List。
 * 这里的 try-catch 就是保证消费失败时主动抛出异常，让容器感知到"这条没处理成功"。
 *
 * @see AiReplyProducer   生产端（XADD）
 * @see StreamConstants   Stream Key / Field 常量定义
 */
@Component
// RedisTemplate 不存在时（Redis 没连上）不创建消费者，
// 此时 Producer 侧 catch 异常后降级为同步写库，整条 Stream 链路静默跳过
@ConditionalOnBean(RedisTemplate.class)
public class AiReplyConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(AiReplyConsumer.class);

    private final ConsultationMessageService consultationMessageService;

    public AiReplyConsumer(ConsultationMessageService consultationMessageService) {
        this.consultationMessageService = consultationMessageService;
    }

    /**
     * 消费一条 Stream 消息。
     *
     * @param record Stream 中的一条记录，{@code record.getValue()} 返回 Map<String, String>，
     *               key 就是 StreamConstants.FIELD_* 那几个常量
     */
    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        var fields = record.getValue();

        try {
            // --- 1. 取 dbSessionId 并校验 ---
            String sid = fields.get(StreamConstants.FIELD_DB_SESSION_ID);
            if (sid == null) {
                throw new IllegalArgumentException("Stream 消息缺少 dbSessionId 字段，无法落库");
            }
            Long dbSessionId = Long.parseLong(sid);

            // --- 2. 取 AI 回复内容 ---
            String content = fields.getOrDefault(StreamConstants.FIELD_CONTENT, "");
            String aiModel = fields.getOrDefault(StreamConstants.FIELD_AI_MODEL, "unknown");

            // 内容为空直接跳过（正常情况不会发生，但防御一下）
            if (content.isEmpty()) {
                log.warn("Stream 消息 content 为空，跳过, recordId={}", record.getId());
                // 不抛异常 → 让容器 ACK，避免空消息卡在 Pending List 里反复被拉取
                return;
            }

            // --- 3. 落库 ---
            // 这里复用原来的 saveAimessage 方法，逻辑不变：
            // INSERT INTO consultation_message (sessionId, content, senderType) VALUES (?, ?, 2)
            consultationMessageService.saveAimessage(dbSessionId, content, aiModel);
            log.debug("Redis Stream 消费完成并落库, sessionId={}, model={}, recordId={}",
                    dbSessionId, aiModel, record.getId());

        } catch (Exception e) {
            // 消费失败 → 主动抛出，让 ListenerContainer 不 ACK
            // 消息留在 Pending List，等待重平衡或 XCLAIM 重新领取
            log.error("Redis Stream 消费失败, recordId={}, 原因: {}", record.getId(), e.getMessage(), e);
            throw new RuntimeException("Redis Stream 消费失败: " + e.getMessage(), e);
        }
    }
}
