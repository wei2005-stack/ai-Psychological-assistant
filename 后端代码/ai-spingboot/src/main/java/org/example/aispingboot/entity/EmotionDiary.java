package org.example.aispingboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记实体，对应 emotion_diary 表。
 *
 * <p>表上建有唯一索引 {@code user_date_unique (user_id, diary_date)}，
 * 表示「一个用户一天只能有一条日记」，因此新增时按 (用户, 日期) 做 upsert，
 * 用户当天重复提交会覆盖而不是产生第二条记录。</p>
 */
@Data
@TableName("emotion_diary")
@Builder
public class EmotionDiary {

    /** 日记 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 日记日期（唯一索引的一部分） */
    @TableField("diary_date")
    private LocalDate diaryDate;

    /** 情绪评分 1-10，前端用 el-rate 展示 */
    @TableField("mood_score")
    private Integer moodScore;

    /** 主要情绪：开心 / 平静 / 焦虑 / 悲伤 / 兴奋 / 疲惫 / 惊讶 / 困惑 */
    @TableField("dominant_emotion")
    private String dominantEmotion;

    /** 情绪触发因素 */
    @TableField("emotion_triggers")
    private String emotionTriggers;

    /** 日记正文 */
    @TableField("diary_content")
    private String diaryContent;

    /** 睡眠质量 1-5 */
    @TableField("sleep_quality")
    private Integer sleepQuality;

    /** 压力水平 1-5 */
    @TableField("stress_level")
    private Integer stressLevel;

    /**
     * AI 情绪分析结果（JSON 字符串）。
     * 用字符串而不是 JSON 列类型，是因为 MyBatis-Plus 默认不带 JSON 类型处理器，
     * 字符串存取更稳；内容由 EmotionAnalyzer 生成，管理端详情页直接 JSON.parse 展示。
     */
    @TableField("ai_emotion_analysis")
    private String aiEmotionAnalysis;

    /** AI 分析更新时间 */
    @TableField("ai_analysis_updated_at")
    private LocalDateTime aiAnalysisUpdatedAt;

    /** 创建时间（数据库默认值维护） */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间（数据库 ON UPDATE 维护） */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
