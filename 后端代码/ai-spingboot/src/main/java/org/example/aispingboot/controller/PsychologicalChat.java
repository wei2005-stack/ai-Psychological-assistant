package org.example.aispingboot.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.validation.Valid;
import org.example.aispingboot.AiService.PsychologicalSupportService;
import org.example.aispingboot.AiService.StructOutPut;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.command.ConsultationStreamDTO;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.DTO.response.EmotionAnalysisResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.example.aispingboot.service.EmotionAnalyzer;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 心理咨询接口。
 * 提供会话创建、SSE 流式对话、会话情绪分析三个入口，用户身份从 JWT 中解析。
 */
@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    /** 对话记忆：删除会话时同步清理，避免 Redis 里残留已删除会话的上下文 */
    @Autowired
    private ChatMemory chatMemory;

    /**
     * 创建新的咨询会话。
     *
     * @param createDTO 会话标题与首条消息
     * @return 新会话信息
     */
    @PostMapping("/session/start")
    public Result<StructOutPut.StreamChatSession> startSession(@Valid @RequestBody ConsultationSessionCreateDTO createDTO) {
        // 获取当前用户
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        Long userId = jwt.getClaim("userId").asLong();
        StructOutPut.StreamChatSession session = psychologicalSupportService.startSession(userId, createDTO);
        return Result.ok(session);
    }

    /**
     * SSE 流式对话接口。
     *
     * <p>请求方式：POST /api/psychological-chat/stream，请求体为 JSON（sessionId + userMessage），
     * 响应类型为 {@code text/event-stream}；前端用 fetchEventSource 持续接收，逐段拼接 AI 回复。</p>
     *
     * <p>事件协议（前端按 {@code event} 字段分支处理）：</p>
     * <ul>
     *   <li>{@code message}：正常增量事件，data 为统一包装的 Result，
     *       业务数据形如 {@code {"content":"文本片段","type":"normal"}}；</li>
     *   <li>{@code done}：生成结束标记，data 为 {@code {}}，前端收到后主动关闭连接；</li>
     *   <li>{@code error}：仅用于 userId 为空时的兜底，携带未登录错误码；
     *       模型调用失败不会被包装成该事件，而是走 Flux 的错误通道。</li>
     * </ul>
     *
     * @param streamDTO 会话 ID 与用户消息
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ConsultationStreamDTO streamDTO) {
        // 第 1 步：鉴权。
        // getCurrentToken 优先读取 JWT 过滤器写入的 jwtToken 请求属性，取不到再回退到请求头；
        // verifyToken 会校验签名、签发者和过期时间，失败时抛出 JWTVerificationException，
        // 由全局异常处理器返回 401，轮不到下面的 SSE 逻辑
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        // 从 JWT 载荷中取用户 ID。验签通过的 token 一定带 userId，这里的 null 判断属于防御性兜底
        Long userId = jwt.getClaim("userId").asLong();

        if (userId == null) {
            // 用户未登录，返回错误事件
            // 故意仍用 ServerSentEvent 包装，保证响应类型仍是 text/event-stream，前端按 SSE 协议解析而不会报解析错误
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    // 错误事件数据，包含错误码、错误信息和用户未登录的提示
                    .data(JSONUtil.toJsonStr(Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), "用户未登录")))
                    .build());
        }

        // 第 2 步：开始流式对话。
        // 业务层返回的是纯文本分片流（Flux<String>），此刻仍是冷流：
        // 查库、写消息、调用大模型都要等客户端订阅后才真正执行
        return psychologicalSupportService.streamPsychologicalChat(streamDTO.getSessionId(), streamDTO.getUserMessage())
                // 第 3 步：把每个文本分片映射成一个 SSE 的 message 事件。
                // data 用 Result.ok 统一包装，前端解析 payload.data.content 后逐段累加到 AI 气泡上
                .map(Fragment -> {
                    return ServerSentEvent.<String>builder()
                            .event("message")
                            .data(JSONUtil.toJsonStr(Result.ok(Map.of("content", Fragment, "type", "normal"))))
                            .build();
                })
                // 第 4 步：模型正常生成完毕后，在流末尾追加一个 done 事件作为结束标记。
                // 前端收到 done 会调用 ctrl.abort() 关闭连接，并触发情绪分析刷新
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{}")
                        .build()
                ))
                // 第 5 步：每个事件延迟 50ms 再下发。
                // 模型分片往往一次返回多段，这里人为放慢，形成"逐字输出"的打字机观感；
                // 代价是总耗时会随分片数线性增加（约 n × 50ms）
                .delayElements(Duration.ofMillis(50)); // 添加延迟确保流式数据的体验
    }

    /**
     * 查询会话的情绪与风险分析结果。
     *
     * <p>数据来源：读取该会话最近 20 条用户消息，交给 {@link EmotionAnalyzer} 做规则分析，
     * 不调用大模型，毫秒级返回。风险等级取这些消息中的最高值，
     * 保证历史里出现过危机表达时不会被后续的正常消息覆盖。</p>
     *
     * <p>同时做了归属校验：只能查询自己创建的会话，避免通过枚举 session_1、session_2
     * 越权读取他人对话内容。</p>
     *
     * @param sessionId 对外会话 ID，形如 session_17
     * @return 情绪分析结果，前端用它渲染情绪标签、强度条和风险提示
     */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<EmotionAnalysisResponseDTO> getSessionEmotion(@PathVariable String sessionId) {
        // 第 1 步：鉴权，拿到当前用户 ID
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        Long userId = jwt.getClaim("userId").asLong();

        // 第 2 步：解析对外 ID
        Long dbSessionId = psychologicalSupportService.extractSessionId(sessionId);
        if (dbSessionId == null) {
            return Result.<EmotionAnalysisResponseDTO>error(ResultCode.PARAM_ERROR.getCode(),
                    ResultCode.PARAM_ERROR.getMsg(), null);
        }

        // 第 3 步：归属校验，会话不存在或不属于当前用户时不返回任何内容
        ConsultationSession session = consultationSessionService.getSessionById(dbSessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            return Result.<EmotionAnalysisResponseDTO>error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }

        // 第 4 步：取最近 20 条用户消息拼成分析样本
        List<ConsultationMessage> recent = consultationMessageService.listRecentUserMessages(dbSessionId, 20);
        String sample = recent.stream()
                .map(ConsultationMessage::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        EmotionAnalyzer.EmotionResult result = EmotionAnalyzer.analyze(sample);

        EmotionAnalysisResponseDTO dto = EmotionAnalysisResponseDTO.builder()
                .sessionId(sessionId)
                .primaryEmotion(result.primaryEmotion())
                .emotionScore(result.emotionScore())
                .isNegative(result.isNegative())
                .riskLevel(result.riskLevel())
                .riskDescription(EmotionAnalyzer.riskDescription(result.riskLevel()))
                .suggestion(result.suggestion())
                .improvementSuggestions(result.improvementSuggestions())
                .matchedKeywords(result.matchedKeywords())
                .analyzedMessageCount(recent.size())
                .build();

        return Result.ok(dto);
    }

    /* ==================== 会话列表 / 详情 / 删除 ==================== */

    /**
     * 查询当前用户的会话列表（分页，按开始时间倒序）。
     *
     * <p>返回结构与前端 consultation.vue 的会话列表一致：
     * id、sessionTitle、startedAt、lastMessageContent、messageCount、durationMinutes。</p>
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 含 records 和 total 的分页结构
     */
    @GetMapping("/sessions")
    public Result<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        Long userId = currentUserId();
        List<ConsultationSession> sessions = consultationSessionService.listByUser(userId, pageNum, pageSize);

        List<Map<String, Object>> records = new ArrayList<>(sessions.size());
        for (ConsultationSession s : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("sessionId", "session_" + s.getId());
            item.put("sessionTitle", s.getSessionTitle());
            item.put("startedAt", s.getStartedAt() == null ? null
                    : s.getStartedAt().format(SESSION_TIME_FORMATTER));

            // 列表预览：最后一条消息内容 + 消息条数
            ConsultationMessageResponseDTO last = consultationMessageService.getLastMessageBySessionId(s.getId());
            item.put("lastMessageContent", last == null ? "" : trimForPreview(last.getContent()));
            item.put("messageCount", consultationMessageService.getMessageCountBySessionId(s.getId()));

            // 会话持续时长（分钟）：从会话开始到最后一条消息
            long minutes = 0L;
            if (s.getStartedAt() != null && last != null && last.getCreatedAt() != null) {
                minutes = Math.max(0L, Duration.between(s.getStartedAt(), last.getCreatedAt()).toMinutes());
            }
            item.put("durationMinutes", minutes);
            records.add(item);
        }

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", records);
        page.put("total", consultationSessionService.countByUser(userId));
        return Result.ok(page);
    }

    /**
     * 查询某个会话的消息明细，用于点击历史会话后回填聊天记录。
     *
     * @param sessionId 数据库会话 ID（前端传的是数字 id）
     * @return 消息列表，按时间正序
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessage>> listSessionMessages(@PathVariable Long sessionId) {
        Long userId = currentUserId();
        ConsultationSession session = consultationSessionService.getSessionById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            return Result.<List<ConsultationMessage>>error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(consultationMessageService.listBySessionId(sessionId));
    }

    /**
     * 删除会话及其全部消息。只允许删除自己的会话。
     *
     * @param sessionId 数据库会话 ID
     * @return 空结果表示删除成功
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        Long userId = currentUserId();
        ConsultationSession session = consultationSessionService.getSessionById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            return Result.<Void>error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        // 先删消息再删会话：即使中途失败，也不会留下"有会话没消息"的孤儿数据
        consultationMessageService.deleteBySessionId(sessionId);
        consultationSessionService.deleteById(sessionId);
        // 顺带清理 Redis 中的对话记忆，避免删除后重启又"复活"上下文
        chatMemory.clear("conversation_session_" + sessionId);
        return Result.ok();
    }

    /** 从当前请求的 JWT 中解析用户 ID（token 由 JwtAuthticationFilter 写入请求属性）。 */
    private Long currentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    /** 会话列表时间展示格式 */
    private static final java.time.format.DateTimeFormatter SESSION_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 会话列表预览文案截断长度 */
    private static String trimForPreview(String content) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 40 ? oneLine.substring(0, 40) + "..." : oneLine;
    }
}
