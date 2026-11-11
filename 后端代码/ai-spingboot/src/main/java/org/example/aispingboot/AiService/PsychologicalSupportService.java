package org.example.aispingboot.AiService;

import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.mq.AiReplyProducer;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.example.aispingboot.service.EmotionAnalyzer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    /** Redis Stream 生产者 —— AI 回复异步落库 */
    @Autowired
    private AiReplyProducer aiReplyProducer;

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
     *   <li>保存本轮用户消息（数据库唯一索引保证同会话同内容不会重复入库，
     *       命中时 {@link ConsultationMessageService} 会自动返回已存在记录，不会抛异常）；</li>
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

            // 第 2 步：先做情绪与危机识别（规则驱动，不依赖模型）。
            // 放在模型调用之前，是因为风险等级要用来决定"是否强制追加热线兜底"，
            // 这个判断不能被模型输出左右，必须是确定性的。
            EmotionAnalyzer.EmotionResult emotion = EmotionAnalyzer.analyze(userMessage);

            // 第 3 步：保存本轮用户消息，并把识别出的情绪写入 emotion_tag 字段。
            // 数据库唯一索引 (session_id, sender_type, content(200)) 负责防重：
            // 同一会话里、同一个发送者的同一条消息，数据库层面只允许一条；
            // Service 层捕获 DuplicateKeyException 后返回已存在的记录，不中断业务流程。
            // 这样既防住了"startSession 已存过 + stream 又来一次"的初始消息重复，
            // 也防住了任何后续轮次的并发重复请求。
            consultationMessageService.saveUserMessage(dbSessionId, userMessage, emotion.primaryEmotion());

            // 风险等级 >= 2（预警 / 危机）时，回复末尾必须追加热线与求助指引，
            // 避免模型漏掉关键的安全提示
            boolean needCrisisAppendix = emotion.riskLevel() >= 2;

            // ==================== 一、准备对话上下文 ====================
            // 进行流式对话
            // 生成对话记忆管理

            // 会话级记忆的 key：用 sessionId 做隔离，保证不同会话的历史互不串扰；
            // 同一会话的多轮对话都共享这一个 conversationId 下的记忆
            String conversationId = "conversation_" + sessionId;

            // 注意：这里不再手动 chatMemory.add(userMessage)。
            // ChatClient 默认挂载的 MessageChatMemoryAdvisor 会在调用前后自动完成
            // 「读历史 + 写入本轮用户消息 + 写入模型回复」三件事；
            // 早期版本这里又手动 add 了一次，导致 Redis 里每条消息都存两遍
            // （E2E 实测 LLEN=4，内容为 USER,USER,ASSISTANT,ASSISTANT，白白浪费上下文窗口）。

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
                        // 高风险会话：把热线兜底文案追加到模型回复后面。
                        // 用确定性文本兜底，而不是指望模型自己想起来给热线，
                        // 保证「识别到风险 → 一定出现求助渠道」这个安全承诺成立
                        if (needCrisisAppendix) {
                            String appendix = EmotionAnalyzer.buildCrisisAppendix();
                            fullResponse.append(appendix);
                            // 先推送给前端，用户能立刻看到热线信息
                            sink.next(appendix);
                        }

                        String completeRes = fullResponse.toString();
                        // AI 回复异步落库：通过 Redis Stream 交给消费者，
                        // SSE 连接不用等 MySQL 写完；Redis 不可用时生产者自动降级为同步写库。
                        // 落库内容包含兜底文案，保证数据库记录与用户看到的回复一致
                        aiReplyProducer.publish(dbSessionId, completeRes, "openai");

                        // 对话记忆的写入由 MessageChatMemoryAdvisor 在流结束后自动完成，
                        // 这里不再手动 chatMemory.add(assistant)，否则同一回复会存两遍

                        // 通知前端流已结束，SSE 连接可以立即关闭
                        sink.complete();
                    })
                    // 调用过程中出现异常（鉴权失败、超时、网络中断等）时回调
                    .doOnError(error -> {
                        if (emotion.riskLevel() >= 3) {
                            // 危机场景下模型调用失败，仍然要把热线送达用户——
                            // 这是兜底逻辑最该生效的时刻，直接推送兜底文案并正常结束流
                            sink.next(EmotionAnalyzer.buildCrisisAppendix());
                            sink.complete();
                        } else {
                            // 普通场景把错误透传给 sink，前端收到 error 事件后结束接收
                            sink.error(error);
                        }
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
