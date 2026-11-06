package org.example.aispingboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.mapper.ConsultationMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 咨询消息业务服务。
 * 负责用户消息、AI 消息的保存，以及会话消息数量统计和最后一条消息查询。
 */
@Service
public class ConsultationMessageService {
    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    /**
     * 保存一条用户消息。
     *
     * @param sessionId  会话 ID
     * @param content    消息内容
     * @param emotion_tag 情绪标签，可为 null
     * @return 保存后的消息实体
     */
    public ConsultationMessage saveUserMessage(Long sessionId, String content, String emotion_tag) {
        // 构建用户消息实体
         ConsultationMessage userMessage = ConsultationMessage.builder()
            .sessionId(sessionId)
            .senderType(1)
            .messageType(1)
            .content(content)
            .emotionTag(emotion_tag)
            .createdAt(LocalDateTime.now())
            .build();

        consultationMessageMapper.insert(userMessage);
        return userMessage;
    }

    /**
     * 保存一条 AI 回复消息。
     *
     * @param sessionId 会话 ID
     * @param content   AI 完整回复
     * @param aiModel   使用的模型标识
     * @return 保存后的消息实体
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
        // 插入数据库
        consultationMessageMapper.insert(message);
        return message;
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

        // 手动逐字段赋值，确保转换的准确性和可控性
        ConsultationMessageResponseDTO responseDTO = new ConsultationMessageResponseDTO();
        responseDTO.setId(message.getId());
        responseDTO.setSessionId(message.getSessionId());
        responseDTO.setSenderType(message.getSenderType());
        responseDTO.setMessageType(message.getMessageType());
        responseDTO.setContent(message.getContent());
        responseDTO.setEmotionTag(message.getEmotionTag());
        responseDTO.setAiModel(message.getAiModel());
        responseDTO.setCreatedAt(message.getCreatedAt());

        // 设置描述字段（通过实体方法获取）
        responseDTO.setSenderTypeDesc(message.getSenderTypeDesc());
        responseDTO.setMessageTypeDesc(message.getMessageTypeDesc());

        // 计算消息长度
        responseDTO.calculateContentLength();

        return responseDTO;
    }
}
