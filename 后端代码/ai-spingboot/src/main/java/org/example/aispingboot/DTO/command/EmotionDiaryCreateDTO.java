package org.example.aispingboot.DTO.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 情绪日记提交参数，对应前端 emotionDiary.vue 的表单。
 *
 * <p>字段名与前端 `diaryForm` 完全一致，直接 JSON 映射，不需要前端做二次转换。</p>
 */
@Data
public class EmotionDiaryCreateDTO {

    /**
     * 日记日期，格式 yyyy-MM-dd。
     * 前端默认填今天；为空时后端按服务器当天日期兜底。
     */
    private LocalDate diaryDate;

    /** 情绪评分 1-10，前端必填 */
    @NotNull(message = "情绪评分不能为空")
    @Min(value = 1, message = "情绪评分最小为 1")
    @Max(value = 10, message = "情绪评分最大为 10")
    private Integer moodScore;

    /** 主要情绪，如 开心 / 平静 / 焦虑 */
    @Size(max = 50, message = "主要情绪长度不能超过 50 个字符")
    private String dominantEmotion;

    /** 情绪触发因素 */
    @Size(max = 1000, message = "情绪触发因素长度不能超过 1000 个字符")
    private String emotionTriggers;

    /** 日记正文 */
    @Size(max = 2000, message = "日记内容长度不能超过 2000 个字符")
    private String diaryContent;

    /** 睡眠质量 1-5 */
    @Min(value = 1, message = "睡眠质量最小为 1")
    @Max(value = 5, message = "睡眠质量最大为 5")
    private Integer sleepQuality;

    /** 压力水平 1-5 */
    @Min(value = 1, message = "压力水平最小为 1")
    @Max(value = 5, message = "压力水平最大为 5")
    private Integer stressLevel;
}
