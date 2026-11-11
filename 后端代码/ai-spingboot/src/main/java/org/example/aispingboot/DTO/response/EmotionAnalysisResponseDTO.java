package org.example.aispingboot.DTO.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话情绪分析结果。
 *
 * <p>字段与前端 consultation.vue 的情绪面板一一对应：
 * primaryEmotion 主导情绪、emotionScore 强度、isNegative 是否负性、
 * riskLevel 风险 0-3、suggestion 建议、improvementSuggestions 小行动。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmotionAnalysisResponseDTO {

    /** 对外会话 ID，形如 session_17 */
    private String sessionId;

    /** 主导情绪：焦虑 / 悲伤 / 愤怒 / 恐惧 / 沮丧 / 压力 / 平静 / 快乐 / 中性 */
    private String primaryEmotion;

    /** 情绪强度 0-100，越高代表负性情绪越强 */
    private Integer emotionScore;

    /** 是否为负性情绪 */
    private Boolean isNegative;

    /** 风险等级：0 正常 / 1 关注 / 2 预警 / 3 危机 */
    private Integer riskLevel;

    /**
     * 风险等级对应的说明文案。
     * 前端「温馨提示」卡片直接展示该字段；风险 3 时包含热线号码。
     */
    private String riskDescription;

    /** 温和建议 */
    private String suggestion;

    /** 可执行的小行动 */
    private List<String> improvementSuggestions;

    /** 命中的风险关键词，便于排查误判 */
    private List<String> matchedKeywords;

    /** 本次分析覆盖的用户消息条数 */
    private Integer analyzedMessageCount;
}
