package org.example.aispingboot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * AI 对话客户端配置。
 * 负责创建对话记忆和 ChatClient，供心理疏导业务调用。
 *
 * <p>启动时会主动 PING Redis 来验证连接状态，并打印诊断日志，
 * 方便排查"Redis 明明在运行但 Spring Boot 没连上"这类问题。</p>
 */
@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    /**
     * 启动时主动测试 Redis 连接并打印详细诊断信息。
     * 无论是否成功都会打印，方便排查。
     */
    @Bean
    public CommandLineRunner redisConnectionChecker(
            org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            RedisTemplate<String, Object> redisTemplate) {
        return args -> {
            RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();

            if (factory == null) {
                log.error("╔════════════════════════════════════════════════════════════╗");
                log.error("║ ❌ RedisConnectionFactory 未创建！                          ║");
                log.error("║    可能原因：                                               ║");
                log.error("║    1. spring-boot-starter-data-redis 依赖未正确下载          ║");
                log.error("║    2. spring.data.redis 配置未被正确加载                     ║");
                log.error("║    3. Spring Boot Redis 自动配置被排除了                     ║");
                log.error("║                                                            ║");
                log.error("║ 降级为内存存储，对话历史重启后丢失。                         ║");
                log.error("╚════════════════════════════════════════════════════════════╝");
                return;
            }

            log.info("🔍 检测到 RedisConnectionFactory，开始测试连接...");
            log.info("   连接工厂类型: {}", factory.getClass().getName());

            try (RedisConnection conn = factory.getConnection()) {
                String pong = conn.ping();
                log.info("╔════════════════════════════════════════════════════════════╗");
                log.info("║ ✅ Redis 连接成功！PING → {}                                 ║", pong);
                log.info("╚════════════════════════════════════════════════════════════╝");
            } catch (Exception e) {
                log.error("╔════════════════════════════════════════════════════════════╗");
                log.error("║ ❌ Redis 连接失败！                                         ║");
                log.error("║    错误类型: {}", e.getClass().getName());
                log.error("║    错误信息: {}", e.getMessage());
                log.error("║                                                            ║");
                log.error("║    排查步骤：                                               ║");
                log.error("║    1. 确认 redis-server.exe 窗口没关闭（看里面有没有      ║");
                log.error("║       'Ready to accept connections' 这行）                 ║");
                log.error("║    2. 打开 cmd 执行:  telnet localhost 6379                ║");
                log.error("║    3. 如果 telnet 也连不上 → Redis 没启动/被防火墙拦截      ║");
                log.error("║    4. 如果 telnet 能连上 → Spring 配置问题，检查 yml         ║");
                log.error("║                                                            ║");
                log.error("║ 降级为内存存储，对话历史重启后丢失。                         ║");
                log.error("╚════════════════════════════════════════════════════════════╝");
            }
        };
    }

    // ==================== ChatMemory Bean ====================

    /**
     * ChatMemory Bean。
     *
     * <p>先尝试用 Redis 持久化仓库，如果 Redis 连接失败则自动降级为内存仓库。
     * 通过在 Bean 方法内部做 try-catch 实现，不依赖 Spring 的条件注解，
     * 这样能把"连不上 Redis"的具体错误打印出来。</p>
     */
    @Bean
    public ChatMemory chatMemory(
            org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectMapper objectMapper) {

        RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();

        // 情况 1：连 RedisConnectionFactory 都没有 → 直接内存
        if (factory == null) {
            log.warn("RedisConnectionFactory 不存在，使用内存存储");
            return MessageWindowChatMemory.builder().maxMessages(30).build();
        }

        // 情况 2：有工厂但实际连不上 → try-catch 降级
        try (RedisConnection conn = factory.getConnection()) {
            conn.ping(); // 主动 PING 一下确认能通

            log.info("✨ 使用 Redis 持久化对话记忆（TTL 30 天，滑动窗口 30 条）");

            // 对话记忆单独使用 StringRedisTemplate：
            // 全局 RedisTemplate<String,Object> 的值序列化器开了 defaultTyping（会二次包装类型信息），
            // 直接复用会让消息 JSON 读回来变成 List/Map 而非字符串，导致反序列化失败。
            StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(factory);
            stringRedisTemplate.afterPropertiesSet();

            RedisChatMemoryRepository repo = new RedisChatMemoryRepository(
                    stringRedisTemplate, objectMapper, 30L);
            return MessageWindowChatMemory.builder()
                    .chatMemoryRepository(repo)
                    .maxMessages(30)
                    .build();
        } catch (Exception e) {
            log.error("Redis 连接测试失败，降级为内存存储。原因：{}", e.getMessage());
            return MessageWindowChatMemory.builder().maxMessages(30).build();
        }
    }

    // ==================== ChatClient ====================

    /**
     * 创建名为 open-ai 的 ChatClient。
     * 默认挂载对话记忆 Advisor，并设置心理疏导场景的系统提示词。
     */
    @Bean("open-ai")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel, ChatMemory chatMemory) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultSystem("你是一个专业的心理疏导师，温和耐心，善于倾听，能够提供专业的心理支持和建议")
                .build();
    }
}
