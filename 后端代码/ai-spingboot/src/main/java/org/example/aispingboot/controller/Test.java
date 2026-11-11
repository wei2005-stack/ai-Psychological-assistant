package org.example.aispingboot.controller;

import org.example.aispingboot.common.Result;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 连通性和 Redis 测试接口。
 * 用于确认后端服务已启动、Redis 连接是否正常、ChatMemory 是否生效。
 * 所有接口无需登录即可访问。
 */
@RestController
@RequestMapping("/api")
public class Test {

    private final ChatMemory chatMemory;

    public Test(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @GetMapping("/test")
    public Result<String> test() {
        return Result.ok("hello world");
    }

    /**
     * 测试 Redis 连接状态。
     * 返回 ChatMemory 类型（Redis 版 or 内存版）。
     */
    @GetMapping("/test/redis")
    public Result<Map<String, Object>> testRedis() {
        Map<String, Object> info = new HashMap<>();
        info.put("chatMemoryClass", chatMemory.getClass().getName());

        // 检查底层 Repository 类型（通过反射或包装类获取）
        // MessageWindowChatMemory 内部有 chatMemoryRepository 字段
        try {
            java.lang.reflect.Field repoField = chatMemory.getClass().getDeclaredField("chatMemoryRepository");
            repoField.setAccessible(true);
            Object repo = repoField.get(chatMemory);
            info.put("repositoryClass", repo.getClass().getName());
        } catch (Exception e) {
            info.put("repositoryClass", "unknown: " + e.getMessage());
        }

        return Result.ok(info);
    }

    /**
     * 直接向 ChatMemory 写入一条测试消息，验证 Redis 是否真正在持久化。
     * 然后读回来再写一条，最后清理。
     */
    @GetMapping("/test/redis/chat")
    public Result<Map<String, Object>> testRedisChat(@RequestParam(defaultValue = "test_conv") String convId) {
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", convId);

        try {
            // 先读一次（应该是空的）
            List<org.springframework.ai.chat.messages.Message> before = chatMemory.get(convId);
            result.put("messageCountBefore", before.size());

            // 写一条 UserMessage
            java.util.List<org.springframework.ai.chat.messages.Message> msgs = java.util.List.of(
                    new org.springframework.ai.chat.messages.UserMessage("Redis ChatMemory 测试消息：" + System.currentTimeMillis())
            );
            chatMemory.add(convId, msgs);

            // 再读一次（应该有 1 条了）
            List<org.springframework.ai.chat.messages.Message> after = chatMemory.get(convId);
            result.put("messageCountAfter", after.size());
            result.put("lastMessage", after.isEmpty() ? "none" : after.get(after.size() - 1).getText());

            // 清理
            chatMemory.clear(convId);
            result.put("clearDone", true);
            result.put("chatMemoryClass", chatMemory.getClass().getName());
        } catch (Exception e) {
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        return Result.ok(result);
    }
}
