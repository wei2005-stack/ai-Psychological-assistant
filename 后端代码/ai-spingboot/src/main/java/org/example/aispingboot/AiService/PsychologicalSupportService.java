package org.example.aispingboot.AiService;

import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 心理疏导核心服务。
 * 负责创建咨询会话、保存消息、维护对话记忆，并调用 ChatClient 进行流式回复。
 */
@Service
public class PsychologicalSupportService {
    @Autowired
    @Qualifier("open-ai")
    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    /**
     * 创建新的咨询会话。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>在 {@code consultation_session} 表落库会话记录，拿到自增主键；</li>
     *   <li>把首条用户消息写入 {@code consultation_message} 表，作为会话的第一条上下文；</li>
     *   <li>用数据库主键拼出对外会话 ID（{@code session_{id}}）并返回给前端。</li>
     * </ol>
     *
     * <p>对外只暴露带前缀的 ID，不直接返回数据库主键，后续对话时由
     * {@link #extractSessionId(String)} 还原，防止裸 ID 被随意猜测。</p>
     *
     * @param userId    当前登录用户 ID（由 JWT 过滤器解析后传入），用于会话归属和数据隔离
     * @param createDTO 会话标题和首条消息
     * @return 会话信息（对外 sessionId、起止时间、状态等），供前端做流式对话和状态管理
     */
    public StructOutPut.StreamChatSession startSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 第 1 步：创建数据库会话记录，返回值中带有自增主键 consultationSession.getId()
        ConsultationSession consultationSession = consultationSessionService.createSession(userId, createDTO);

        // 第 2 步：将初始用户消息保存到 Message 表（情绪标签暂不识别，传 null）
        // 提前落库的好处：用户即使还没开始流式对话，会话详情页也能看到首条消息
        consultationMessageService.saveUserMessage(consultationSession.getId(), createDTO.getInitialMessage(), null);

        // 第 3 步：创建会话信息（返回给前端 用于流式对话和状态管理）
        // 对外 ID 加 session_ 前缀，与数据库主键区分；流式对话入口会用 extractSessionId 还原
        String sessionId = "session_" + consultationSession.getId();
        return new StructOutPut.StreamChatSession(
                sessionId,                              // 对外会话 ID，前端后续对话时必须带回
                userId,                                 // 会话所属用户，用于鉴权与数据隔离
                createDTO.getInitialMessage(),          // 首条消息，前端展示会话摘要用
                System.currentTimeMillis(),             // 会话开始时间戳（毫秒）
                System.currentTimeMillis() + 86400000L, // 过期时间戳：开始时间 + 24 小时
                1,                                      // 当前消息数：此时消息表已写入首条用户消息
                "ACTIVE"                                // 会话状态：活跃
        );
    }

    /**
     * 执行流式心理对话。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>解析并校验对外 sessionId，格式必须是 {@code session_{数据库ID}}；</li>
     *   <li>处理初始消息去重：会话只有 1 条消息且内容与该条用户消息完全一致时，
     *       说明该消息在 {@link #startSession} 中已经落库，本轮不再重复保存；</li>
     *   <li>保存本轮用户消息（非初始消息时），保证每轮输入在消息表中恰好一条；</li>
     *   <li>写入对话记忆并调用 ChatClient 流式生成，把分片逐个推给前端；</li>
     *   <li>流正常结束后，把完整 AI 回复落库并写回对话记忆（见下方 doOnComplete）。</li>
     * </ol>
     *
     * <p>注意：本方法返回的是"冷"流，方法体只是注册逻辑，真正的查库、写库和
     * 模型调用都在客户端订阅（subscribe）之后才发生；因此异常不会直接从本方法抛出，
     * 而是通过 {@code sink.error} 在流中传递给前端。</p>
     *
     * @param sessionId   对外会话 ID，格式为 session_{数据库ID}
     * @param userMessage 用户本轮输入
     * @return 逐步输出的 AI 回复文本流
     */
    public Flux<String> streamPsychologicalChat(String sessionId, String userMessage) {
        // Flux.create 提供 sink，手动控制流的三种动作：
        // sink.next("数据1")     —— 发布一个数据分片
        // sink.complete()        —— 正常结束流
        // sink.error(exception)  —— 发布错误并结束流
        // 借助它把"查库 + 调模型 + 推送"这类过程代码适配成响应式数据流
        return Flux.create(sink -> {
            // 第 1 步：把对外 ID 还原成数据库主键；格式不合法时直接以错误结束流，return 终止后续逻辑
            Long dbSessionId = extractSessionId(sessionId);
            if (dbSessionId == null) {
                sink.error(new RuntimeException("会话ID格式错误"));
                return;
            }
 
            // 第 2 步：初始消息去重判断
            // 前端创建会话后一般会立刻用首条消息触发 AI 回复，而这条消息在 startSession 时已落库；
            // 通过"消息总数 = 1 且最后一条是用户消息、内容完全一致"来识别这种重复提交
            // 是否为初始消息
            boolean isInitialMessage = false;
            // 检查是否为初始消息，避免重复保存
            Integer messageCount = consultationMessageService.getMessageCountBySessionId(dbSessionId);
            if (messageCount == 1) {
               ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(dbSessionId);
                // senderType == 1 表示上一条是用户发送的消息（2 为 AI 助手）
                if (lastMessage != null && lastMessage.getSenderType() == 1 && userMessage.equals(lastMessage.getContent())) {
                    isInitialMessage = true;
                }
            }

            // 第 3 步：只有非初始消息才落库，避免同一句话在消息表中存两条
            if (!isInitialMessage) {
                // 保存用户消息到数据库（情绪标签本轮不识别，传 null）
                consultationMessageService.saveUserMessage(dbSessionId, userMessage, null);
            }

            // ==================== 一、准备对话上下文 ====================
            // 进行流式对话
            // 生成对话记忆管理

            // 会话级记忆的 key：用 sessionId 做隔离，保证不同会话的历史互不串扰；
            // 同一会话的多轮对话都共享这一个 conversationId 下的记忆
            String conversationId = "conversation_" + sessionId;

            // 把本轮用户消息写入对话记忆（MessageWindowChatMemory，最多保留最近 30 条）。
            // 注意：ChatClient 已默认挂载 MessageChatMemoryAdvisor（见 ChatClientConfig），
            // 该 Advisor 在发起调用前会自动从 chatMemory 读取该会话历史拼进请求，
            // 因此这里的记忆内容会被带到本轮模型调用中
            List<Message> userMessages = new ArrayList<>();
            userMessages.add(new UserMessage(userMessage));
            chatMemory.add(conversationId, userMessages);

            // 构建 Prompt：显式放入系统提示词，约束 AI 的角色、语气和回复边界；
            // 本轮用户输入则通过下面的 .user(userMessage) 追加，不在 Prompt 里重复放
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT)
            ));

            // 用于存储AI完成的响应：
            // 流式返回是分片到达的，每片先累积到 StringBuilder，
            // 等流正常结束时才拼接成完整文本，用于落库和写回记忆
            StringBuilder fullResponse = new StringBuilder();

            // ==================== 二、发起流式调用并桥接给 SSE ====================
            // 使用chatClient发送消息到Open AI
            chatClient.prompt(prompt)   // 用上面构造的、只含 SystemMessage 的 Prompt 创建请求
                    .user(userMessage)  // 追加本轮用户输入（最终与系统提示词、历史记忆一起组成模型入参）
                    // 把 conversationId 交给 Advisor：MessageChatMemoryAdvisor 以此 key
                    // 从 chatMemory 取出该会话历史，实现多轮上下文记忆
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()   // 流式调用模型（等价 SSE），模型边生成边返回，而不是等全部生成完再返回
                    .content()  // 只取响应中的文本内容，忽略 token 用量等元数据
                    // 每收到一个文本分片：先累积到 fullResponse 供流结束后落库，
                    // 再通过 sink.next 推送给前端，实现逐字输出效果
                    .doOnNext(Fragment -> {
                        fullResponse.append(Fragment);
                        sink.next(Fragment);
                    })
                    // 流正常结束（模型生成完毕）时回调，此时 fullResponse 才是完整回复
                    .doOnComplete(() -> {
                        String completeRes = fullResponse.toString();
                        // 将AI返回的内容保存到数据库（消息来源标记为 openai，对应 senderType=2）
                        consultationMessageService.saveAimessage(dbSessionId, completeRes, "openai");
                        // 添加AI回复到chatMemory，作为下一轮对话的历史上下文
                        List<Message> aiMessages = new ArrayList<>();
                        aiMessages.add(new AssistantMessage(completeRes));
                        chatMemory.add(conversationId, aiMessages);

                        // 通知前端流已结束，SSE 连接可以关闭
                        sink.complete();
                    })
                    // 调用过程中出现异常（鉴权失败、超时、网络中断等）时回调：
                    // 把错误透传给 sink，前端收到 error 事件后结束接收
                    .doOnError(error -> {
                        sink.error(error);
                    })
                    // Reactor 采用惰性执行，不调用 subscribe 就不会真正发请求；
                    // 订阅后数据流才开始流动，上面三个 doOnXxx 回调也才会被触发
                    .subscribe(); // 订阅并启动流
        });
    }

    /**
     * 从对外会话 ID 中解析数据库会话 ID。
     *
     * @param sessionId 形如 session_17 的会话 ID
     * @return 数据库会话 ID，格式不合法时返回 null
     */
    public Long extractSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("session_")) {
            String idStr = sessionId.substring("session_".length());
            return Long.parseLong(idStr);
        }
        return null;
    }
}
