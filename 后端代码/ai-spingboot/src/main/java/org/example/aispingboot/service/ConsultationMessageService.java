package org.example.aispingboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.mapper.ConsultationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 咨询消息业务服务。
 * 负责用户消息、AI 回复的保存，以及会话消息数量统计和最后一条消息查询。
 *
 * <p>消息去重策略：数据库表 consultation_message 上建了唯一索引
 * {@code uk_session_sender_content (session_id, sender_type, content(200))}，
 * 同一个会话里、同一个发送者的同一条消息（按前 200 字前缀判断）只能存一条。
 * 应用层捕获 {@link DuplicateKeyException} 后直接把已存在的那条查出来返回，
 * 比"先查后插"更安全，不会被并发请求击穿。</p>
 */
@Service
public class ConsultationMessageService {
    private static final Logger log = LoggerFactory.getLogger(ConsultationMessageService.class);

    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    /**
     * 保存一条用户消息。
     *
     * <p>命中唯一索引时（同一会话 + 用户 + 相同内容已存在），
     * 不报错，把已存在的那条查出来返回。这样调用方不会拿到重复的 Message，
     * 也不会因为 DuplicateKeyException 中断业务流程。</p>
     *
     * @param sessionId  会话 ID
     * @param content    消息内容
     * @param emotionTag 情绪标签，可为 null
     * @return 保存后的消息实体；命中唯一索引时返回数据库中已有的那条
     */
    public ConsultationMessage saveUserMessage(Long sessionId, String content, String emotionTag) {
        ConsultationMessage userMessage = ConsultationMessage.builder()
                .sessionId(sessionId)
                .senderType(1)
                .messageType(1)
                .content(content)
                .emotionTag(emotionTag)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            consultationMessageMapper.insert(userMessage);
            return userMessage;
        } catch (DuplicateKeyException e) {
            // 唯一索引命中 → 这条消息（或前缀 200 字）已经存过
            log.debug("用户消息重复，已跳过入库。sessionId={}, contentHead={}",
                    sessionId, headOf(content));
            return findMessage(sessionId, 1, content);
        }
    }

    /**
     * 保存一条 AI 回复消息。
     *
     * <p>逻辑与 {@link #saveUserMessage} 对称：命中唯一索引时查已有记录返回，
     * 保证 AI 回复在同一会话内也只能存一条。</p>
     *
     * @param sessionId 会话 ID
     * @param content   AI 完整回复
     * @param aiModel   使用的模型标识
     * @return 保存后的消息实体；命中唯一索引时返回数据库中已有的那条
     */
    public ConsultationMessage saveAimessage(Long sessionId, String content, String aiModel) {
        ConsultationMessage message = ConsultationMessage.builder()
                .sessionId(sessionId)
                .senderType(2)
                .messageType(1)
                .content(content)
                .aiModel(aiModel)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            consultationMessageMapper.insert(message);
            return message;
        } catch (DuplicateKeyException e) {
            log.debug("AI 回复重复，已跳过入库。sessionId={}, contentHead={}",
                    sessionId, headOf(content));
            return findMessage(sessionId, 2, content);
        }
    }

    /**
     * 根据 (sessionId, senderType, content) 精确查一条消息。
     * 用于 DuplicateKeyException 命中时把已存在的那条返回给调用方。
     *
     * <p>注意：这里查完整 content 而不是前缀。唯一索引是按前缀命中的，
     * 但前缀相同不代表正文完全一致——精确匹配才不会把"开头一样但后面不同"
     * 的两条消息当成同一条返回。</p>
     */
    private ConsultationMessage findMessage(Long sessionId, int senderType, String content) {
        LambdaQueryWrapper<ConsultationMessage> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationMessage::getSessionId, sessionId)
         .eq(ConsultationMessage::getSenderType, senderType)
         .eq(ConsultationMessage::getContent, content)
         .last("limit 1");
        ConsultationMessage existed = consultationMessageMapper.selectOne(w);
        if (existed != null) {
            return existed;
        }
        log.warn("唯一索引命中但未精确找到记录，sessionId={}, senderType={}", sessionId, senderType);
        return null;
    }

    /** 取内容前 30 字用于日志，避免整段消息打爆日志。 */
    private static String headOf(String content) {
        if (content == null) return "(null)";
        return content.length() > 30 ? content.substring(0, 30) + "..." : content;
    }

    /**
     * 查询会话最近若干条用户消息，按时间正序返回。
     * 情绪分析接口用它取样本，避免全表扫描整个会话历史。
     *
     * @param sessionId 会话 ID
     * @param limit     最多取多少条
     * @return 用户消息列表，按创建时间从早到晚排列
     */
    public List<ConsultationMessage> listRecentUserMessages(Long sessionId, int limit) {
        LambdaQueryWrapper<ConsultationMessage> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationMessage::getSessionId, sessionId)
         .eq(ConsultationMessage::getSenderType, 1)
         .orderByDesc(ConsultationMessage::getCreatedAt)
         .last("limit " + limit);
        List<ConsultationMessage> list = consultationMessageMapper.selectList(w);
        // 数据库取的是倒序（最近 N 条），反转成正序，便于拼接成自然语序的文本
        Collections.reverse(list);
        return list;
    }

    /**
     * 查询某个会话的全部消息，按时间正序（前端从上往下渲染对话）。
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<ConsultationMessage> listBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByAsc(ConsultationMessage::getCreatedAt)
                .orderByAsc(ConsultationMessage::getId);
        return consultationMessageMapper.selectList(w);
    }

    /**
     * 删除某个会话下的全部消息（删除会话时级联清理）。
     *
     * @param sessionId 会话 ID
     */
    public void deleteBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> w = new LambdaQueryWrapper<>();
        w.eq(ConsultationMessage::getSessionId, sessionId);
        consultationMessageMapper.delete(w);
    }

    /**
     * 统计指定会话的消息数量。
     *
     * @param sessionId 会话 ID
     * @return 消息条数
     */
    public Integer getMessageCountBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId);

        Long count = consultationMessageMapper.selectCount(queryWrapper);
        return count.intValue();
    }

    /**
     * 获取会话的最后一条消息。
     * 按创建时间倒序取第一条，用于判断初始消息是否重复。
     *
     * @param sessionId 会话 ID
     * @return 最后一条消息，不存在时返回 null
     */
    public ConsultationMessageResponseDTO getLastMessageBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByDesc(ConsultationMessage::getCreatedAt)
                .last("limit 1");

        ConsultationMessage lastMessage = consultationMessageMapper.selectOne(queryWrapper);
        return lastMessage != null ? convertToResponseDTO(lastMessage) : null;
    }

    /**
     * 把消息实体转换为响应 DTO。
     * 补充发送者、消息类型描述，并计算内容长度。
     *
     * @param message 消息实体
     * @return 消息响应 DTO，入参为 null 时返回 null
     */
    private ConsultationMessageResponseDTO convertToResponseDTO(ConsultationMessage message) {
        if (message == null) {
            return null;
        }

        ConsultationMessageResponseDTO responseDTO = new ConsultationMessageResponseDTO();
        responseDTO.setId(message.getId());
        responseDTO.setSessionId(message.getSessionId());
        responseDTO.setSenderType(message.getSenderType());
        responseDTO.setMessageType(message.getMessageType());
        responseDTO.setContent(message.getContent());
        responseDTO.setEmotionTag(message.getEmotionTag());
        responseDTO.setAiModel(message.getAiModel());
        responseDTO.setCreatedAt(message.getCreatedAt());

        responseDTO.setSenderTypeDesc(message.getSenderTypeDesc());
        responseDTO.setMessageTypeDesc(message.getMessageTypeDesc());
        responseDTO.calculateContentLength();

        return responseDTO;
    }
}
