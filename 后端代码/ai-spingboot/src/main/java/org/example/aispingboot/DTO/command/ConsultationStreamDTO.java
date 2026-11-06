package org.example.aispingboot.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 流式对话入参。
 * sessionId 为创建会话时返回的 session_{ID}，userMessage 为本轮用户输入。
 */
@Data
public class ConsultationStreamDTO {
    @NotBlank(message = "sessionId不能为空")
    private String sessionId;

    @NotBlank(message = "初始消息不能为空")
    @Size(max = 2000, message = "初始消息长度不能超过2000个字符")
    private String userMessage;
}
