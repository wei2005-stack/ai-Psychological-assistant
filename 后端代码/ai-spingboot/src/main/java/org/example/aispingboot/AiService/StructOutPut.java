package org.example.aispingboot.AiService;

/**
 * AI 对话相关的结构化输出定义。
 */
public class StructOutPut {

    /**
     * 创建会话后的返回结构。
     *
     * @param sessionId      对外会话 ID，格式为 session_{数据库ID}
     * @param userHash       用户 ID
     * @param initialMessage 首条用户消息
     * @param startTime      会话开始时间戳
     * @param expiryTime     会话过期时间戳，默认 24 小时
     * @param messageCount   当前消息数
     * @param status         会话状态
     */
    // 定义会话结构
    public record StreamChatSession(
            String sessionId,
            Long userHash,
            String initialMessage,
            Long startTime,
            Long expiryTime,
            Integer messageCount,
            String status
    ) {}
}
