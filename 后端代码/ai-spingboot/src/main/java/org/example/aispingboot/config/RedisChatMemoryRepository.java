package org.example.aispingboot.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的 Spring AI ChatMemory 仓库实现。
 *
 * <p>Spring AI 1.0.0 未内置 Redis 版 ChatMemoryRepository（该实现从 2.0.0-M2 才加入官方仓库），
 * 因此本类手动实现 {@link ChatMemoryRepository} 接口，用 Redis List 作为消息存储介质。
 * 这样就可以把 {@code MessageWindowChatMemory} 的底层存储从"内存"切换为"Redis"，
 * 对话记忆跨应用重启得以保留。</p>
 *
 * <h3>Redis Key 设计</h3>
 * <pre>
 *   消息列表：spring-ai:chat:memory:{conversationId}   —— Redis List，每个元素是一条 Message 的 JSON
 *   会话索引：spring-ai:chat:memory:conversations       —— Redis Set，所有 conversationId 的集合
 * </pre>
 *
 * <h3>消息序列化格式</h3>
 * <pre>
 *   {"type":"USER","text":"你好","metadata":{}}
 *   {"type":"ASSISTANT","text":"你好！有什么可以帮你的？","metadata":{}}
 *   {"type":"SYSTEM","text":"你是一个心理疏导师","metadata":{}}
 * </pre>
 *
 * <h3>为什么用 StringRedisTemplate 而不是 RedisTemplate&lt;String, Object&gt;（E2E 踩坑记录）</h3>
 * <p>项目全局的 {@code RedisTemplate<String, Object>} 配置了
 * {@code GenericJackson2JsonRedisSerializer + activateDefaultTyping}（为了能还原业务对象的具体类型）。
 * 如果此处复用它，我们写进去的"JSON 字符串"会被再包一层类型信息，读回来时可能已经变成
 * {@code ArrayList<LinkedHashMap>} 而不是 String，导致 {@code readValue(json, Map.class)} 抛
 * {@code MismatchedInputException: Cannot deserialize ... from Array value} —— 表现为对话记忆整体失效。
 * 因此这里单独使用 {@link StringRedisTemplate}：Value 按纯字符串原样存取，
 * Redis 里看到的就是上面那种干净 JSON，读写完全可控。</p>
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    /** Redis Key 前缀：每条会话消息列表 */
    private static final String KEY_PREFIX = "spring-ai:chat:memory:";
    /** Redis Key：所有会话 ID 的集合（用于 findConversationIds） */
    private static final String CONVERSATIONS_KEY = "spring-ai:chat:memory:conversations";

    /**
     * 会话消息的默认过期时间（天）。
     * 心理疏导场景下 30 天足够保留近期对话记忆，过期后自动释放内存。
     * 设为 0 表示永不过期。
     */
    private final long timeToLiveDays;

    /** 纯字符串读写模板：值不会经过 defaultTyping 二次包装 */
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建一个 30 天过期的 RedisChatMemoryRepository。
     */
    public RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this(stringRedisTemplate, objectMapper, 30L);
    }

    /**
     * 允许自定义过期时间的构造器。
     *
     * @param stringRedisTemplate 字符串 Redis 客户端
     * @param objectMapper        用于 Message ↔ JSON 互转（普通业务 mapper，不要开 defaultTyping）
     * @param timeToLiveDays      每条会话消息列表的 TTL（天），0 = 永不过期
     */
    public RedisChatMemoryRepository(StringRedisTemplate stringRedisTemplate,
                                     ObjectMapper objectMapper,
                                     long timeToLiveDays) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.timeToLiveDays = timeToLiveDays;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        List<String> rawList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> messages = new ArrayList<>(rawList.size());
        for (String json : rawList) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                messages.add(deserialize(json));
            } catch (Exception e) {
                // 单条脏数据不应该让整段对话记忆失效，跳过并记录日志
                log.error("反序列化消息失败，跳过该条：conversationId={}, raw={}", conversationId, json, e);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;

        // saveAll 的语义是"替换所有已有消息"，先删再写
        stringRedisTemplate.delete(key);

        List<String> serialized = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            try {
                serialized.add(serialize(msg));
            } catch (JsonProcessingException e) {
                log.error("序列化消息失败，跳过该条：conversationId={}, messageType={}",
                        conversationId, msg.getMessageType(), e);
            }
        }

        if (!serialized.isEmpty()) {
            stringRedisTemplate.opsForList().rightPushAll(key, serialized);
        }

        // 记录会话 ID 到索引 Set
        stringRedisTemplate.opsForSet().add(CONVERSATIONS_KEY, conversationId);

        // 设置过期时间
        if (timeToLiveDays > 0) {
            stringRedisTemplate.expire(key, timeToLiveDays, TimeUnit.DAYS);
            stringRedisTemplate.expire(CONVERSATIONS_KEY, timeToLiveDays, TimeUnit.DAYS);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForSet().remove(CONVERSATIONS_KEY, conversationId);
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> members = stringRedisTemplate.opsForSet().members(CONVERSATIONS_KEY);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(members);
    }

    // ==================== 序列化 / 反序列化 ====================

    /**
     * 把 Message 序列化为 JSON 字符串。
     * 只保留 type、text 和 metadata（toolCalls / media 等高级字段在心理疏导场景中不会用到）。
     */
    private String serialize(Message message) throws JsonProcessingException {
        Map<String, Object> dto = Map.of(
                "type", message.getMessageType().name(),
                "text", message.getText(),
                "metadata", message.getMetadata() != null ? message.getMetadata() : Collections.emptyMap()
        );
        return objectMapper.writeValueAsString(dto);
    }

    /**
     * 把 JSON 字符串还原成对应的 Message 子类。
     * 根据 {@code type} 字段决定是 UserMessage、AssistantMessage 还是 SystemMessage。
     */
    @SuppressWarnings("unchecked")
    private Message deserialize(String json) throws JsonProcessingException {
        Map<String, Object> dto = objectMapper.readValue(json, Map.class);
        String typeStr = (String) dto.get("type");
        String text = (String) dto.getOrDefault("text", "");

        MessageType type;
        try {
            type = MessageType.valueOf(typeStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("未知的消息类型 '{}'，降级为 USER 类型", typeStr);
            type = MessageType.USER;
        }

        return switch (type) {
            // Spring AI 1.0 的 UserMessage 多参数构造器是 private，只能用单参数
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case TOOL -> new AssistantMessage(text);
        };
    }
}
