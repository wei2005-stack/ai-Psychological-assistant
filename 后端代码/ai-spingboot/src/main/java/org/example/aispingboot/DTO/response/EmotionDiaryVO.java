package org.example.aispingboot.DTO.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记视图对象，管理端 emotional.vue 的列表与详情直接使用。
 *
 * <p>相比实体多带了 username / nickname，因为管理端列表要显示「谁写的」，
 * 而 emotion_diary 表里只有 user_id。</p>
 */
@Data
@Builder
public class EmotionDiaryVO {

    /** 日记 ID */
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 日记日期 */
    private LocalDate diaryDate;

    /** 情绪评分 1-10 */
    private Integer moodScore;

    /** 主要情绪 */
    private String dominantEmotion;

    /** 情绪触发因素 */
    private String emotionTriggers;

    /** 日记正文 */
    private String diaryContent;

    /** 睡眠质量 1-5 */
    private Integer sleepQuality;

    /** 压力水平 1-5 */
    private Integer stressLevel;

    /** AI 情绪分析结果（JSON 字符串，前端 JSON.parse 后展示） */
    private String aiEmotionAnalysis;

    /** AI 分析更新时间 */
    private LocalDateTime aiAnalysisUpdatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
