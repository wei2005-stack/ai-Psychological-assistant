package org.example.aispingboot.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.aispingboot.config.StreamConstants;
import org.example.aispingboot.service.ConsultationMessageService;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
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
public class AiReplyConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(AiReplyConsumer.class);

    private final ConsultationMessageService consultationMessageService;
    /** 用于消费成功后显式 XACK（Spring Data Redis 3.5 的监听容器不会自动确认） */
    private final StringRedisTemplate stringRedisTemplate;

    public AiReplyConsumer(ConsultationMessageService consultationMessageService,
                           StringRedisTemplate stringRedisTemplate) {
        this.consultationMessageService = consultationMessageService;
        this.stringRedisTemplate = stringRedisTemplate;
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
                // 空消息也要 ACK，避免卡在 Pending List 里反复被拉取
                acknowledge(record);
                return;
            }

            // --- 3. 落库 ---
            // 这里复用原来的 saveAimessage 方法，逻辑不变：
            // INSERT INTO consultation_message (sessionId, content, senderType) VALUES (?, ?, 2)
            consultationMessageService.saveAimessage(dbSessionId, content, aiModel);
            log.debug("Redis Stream 消费完成并落库, sessionId={}, model={}, recordId={}",
                    dbSessionId, aiModel, record.getId());

            // --- 4. 显式 XACK ---
            // 实测 Spring Data Redis 3.5 的 StreamMessageListenerContainer 只负责投递，
            // 不会在 onMessage 正常返回后代为确认；不手动 ACK 的话消息会永久留在 Pending List
            // （XINFO GROUPS 里 pending 持续增长），既占内存又影响监控判断。
            acknowledge(record);

        } catch (Exception e) {
            // 消费失败 → 主动抛出，让 ListenerContainer 不 ACK
            // 消息留在 Pending List，等待重平衡或 XCLAIM 重新领取
            log.error("Redis Stream 消费失败, recordId={}, 原因: {}", record.getId(), e.getMessage(), e);
            throw new RuntimeException("Redis Stream 消费失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确认消息已被成功处理（XACK）。
     * ACK 失败只记录日志，不影响已经完成的落库结果。
     */
    private void acknowledge(MapRecord<String, String, String> record) {
        try {
            stringRedisTemplate.opsForStream()
                    .acknowledge(StreamConstants.AI_REPLY_CONSUMER_GROUP, record);
        } catch (Exception e) {
            log.warn("Redis Stream ACK 失败, recordId={}, 原因: {}", record.getId(), e.getMessage());
        }
    }
}
