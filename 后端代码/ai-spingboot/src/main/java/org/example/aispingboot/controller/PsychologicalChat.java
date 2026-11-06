package org.example.aispingboot.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.validation.Valid;
import org.example.aispingboot.AiService.PsychologicalSupportService;
import org.example.aispingboot.AiService.StructOutPut;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.command.ConsultationStreamDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.view.Fragment;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * AI 心理咨询接口。
 * 提供会话创建和 SSE 流式对话两个入口，用户身份从 JWT 中解析。
 */
@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

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
}
