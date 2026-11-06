package org.example.aispingboot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 对话客户端配置。
 * 负责创建对话记忆和 ChatClient，供心理疏导业务调用。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 创建基于滑动窗口的对话记忆。
     * 每个会话最多保留最近 30 条消息，避免上下文无限增长。
     *
     * @return ChatMemory 对话记忆实例
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(30) // 保留最新30条消息
                .build();
    }

    /**
     * 创建名为 open-ai 的 ChatClient。
     * 默认挂载对话记忆 Advisor，并设置心理疏导场景的系统提示词。
     *
     * @param openAiChatModel Spring AI 提供的 OpenAI 兼容模型
     * @return 配置完成的 ChatClient
     */
    @Bean("open-ai")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
        // 默认挂载对话记忆 Advisor，用于维护会话上下文
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory()).build())
                // 设置心理疏导场景的系统提示词
                               .defaultSystem("你是一个专业的心理疏导师，温和耐心，善于倾听，能够提供专业的心理支持和建议").build();
    }
}
