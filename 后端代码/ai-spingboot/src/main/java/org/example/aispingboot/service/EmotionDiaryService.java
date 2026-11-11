package org.example.aispingboot.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.aispingboot.DTO.command.EmotionDiaryCreateDTO;
import org.example.aispingboot.DTO.response.EmotionDiaryVO;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.mapper.EmotionDiaryMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 情绪日记业务服务。
 *
 * <p>核心逻辑：</p>
 * <ol>
 *   <li>提交日记时按 (user_id, diary_date) 做 upsert——表上有唯一索引，
 *       同一用户同一天只保留一条记录，重复提交视为修改当天日记；</li>
 *   <li>保存时顺带调用 {@link EmotionAnalyzer} 对「触发因素 + 日记正文」做规则分析，
 *       结果序列化成 JSON 存进 ai_emotion_analysis，供管理端详情页展示；</li>
 *   <li>分析失败不影响日记保存（降级为只存原文），避免规则层异常拖垮主流程。</li>
 * </ol>
 */
@Service
public class EmotionDiaryService {

    private static final Logger log = LoggerFactory.getLogger(EmotionDiaryService.class);

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    /** 管理端列表需要补充用户名/昵称，因此要查用户表 */
    @Autowired
    private UserMapper userMapper;

    /**
     * 新增或更新当天的情绪日记。
     *
     * @param userId 当前登录用户 ID
     * @param dto    前端表单
     * @return 保存后的日记实体
     */
    public EmotionDiary saveOrUpdate(Long userId, EmotionDiaryCreateDTO dto) {
        // 前端不传日期时按服务器当天兜底，保证唯一索引 (user_id, diary_date) 有值可用
        LocalDate diaryDate = dto.getDiaryDate() != null ? dto.getDiaryDate() : LocalDate.now();

        // 先查当天是否已有记录：唯一索引保证最多一条，selectOne 不会返回多行
        EmotionDiary existing = emotionDiaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getUserId, userId)
                        .eq(EmotionDiary::getDiaryDate, diaryDate));

        // 用规则引擎生成 AI 分析结果（与咨询模块共用同一套风险分级，口径一致）
        String analysisJson = buildAnalysisJson(dto);

        if (existing == null) {
            EmotionDiary diary = EmotionDiary.builder()
                    .userId(userId)
                    .diaryDate(diaryDate)
                    .moodScore(dto.getMoodScore())
                    .dominantEmotion(dto.getDominantEmotion())
                    .emotionTriggers(dto.getEmotionTriggers())
                    .diaryContent(dto.getDiaryContent())
                    .sleepQuality(dto.getSleepQuality())
                    .stressLevel(dto.getStressLevel())
                    .aiEmotionAnalysis(analysisJson)
                    .aiAnalysisUpdatedAt(analysisJson == null ? null : LocalDateTime.now())
                    .build();
            emotionDiaryMapper.insert(diary);
            return diary;
        }

        // 已有当天记录 → 覆盖式更新，保留主键与创建时间
        existing.setMoodScore(dto.getMoodScore());
        existing.setDominantEmotion(dto.getDominantEmotion());
        existing.setEmotionTriggers(dto.getEmotionTriggers());
        existing.setDiaryContent(dto.getDiaryContent());
        existing.setSleepQuality(dto.getSleepQuality());
        existing.setStressLevel(dto.getStressLevel());
        existing.setAiEmotionAnalysis(analysisJson);
        existing.setAiAnalysisUpdatedAt(analysisJson == null ? null : LocalDateTime.now());
        emotionDiaryMapper.updateById(existing);
        return existing;
    }

    /**
     * 管理端分页查询所有用户的日记。
     *
     * <p>项目未引入 MyBatis-Plus 分页插件，这里沿用项目已有的
     * 「limit 手写分页 + 单独 count」写法，和会话列表接口保持一致。</p>
     *
     * @param current        页码，从 1 开始（前端 emotional.vue 的字段名是 current）
     * @param size           每页条数
     * @param userId         按用户筛选，可为 null
     * @param moodScoreRange 评分区间，如 "1-3"、"4-6"、"7-10"，可为 null
     * @return { records, total, current, size }
     */
    public Map<String, Object> adminPage(int current, int size, Long userId, String moodScoreRange) {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(EmotionDiary::getUserId, userId);
        }
        // 前端字段名是 moodScreRange（历史拼写错误），这里按 "1-3" 这种格式解析
        if (StrUtil.isNotBlank(moodScoreRange)) {
            String[] parts = moodScoreRange.split("-");
            if (parts.length == 2) {
                try {
                    wrapper.between(EmotionDiary::getMoodScore,
                            Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) {
                    // 区间格式非法时忽略该条件，不影响列表返回
                    log.warn("情绪评分区间格式非法，已忽略: {}", moodScoreRange);
                }
            }
        }
        wrapper.orderByDesc(EmotionDiary::getDiaryDate).orderByDesc(EmotionDiary::getId);

        // 先取总数，再追加 limit（selectCount 已执行完毕，不会被 limit 影响）
        long total = emotionDiaryMapper.selectCount(wrapper);
        int offset = Math.max(0, (current - 1) * size);
        wrapper.last("limit " + offset + "," + size);
        List<EmotionDiary> diaries = emotionDiaryMapper.selectList(wrapper);

        return Map.of(
                "records", toVOList(diaries),
                "total", total,
                "current", current,
                "size", size);
    }

    /**
     * 管理端删除日记。
     *
     * @param id 日记 ID
     * @return true 表示删除成功（记录存在）
     */
    public boolean delete(Long id) {
        return emotionDiaryMapper.deleteById(id) > 0;
    }

    /**
     * 把实体列表转换为视图对象，并批量补齐用户名/昵称。
     * 用一次 selectBatchIds 代替 N 次查用户，避免 N+1 查询。
     */
    private List<EmotionDiaryVO> toVOList(List<EmotionDiary> diaries) {
        if (diaries == null || diaries.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> userIds = diaries.stream()
                .map(EmotionDiary::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<EmotionDiaryVO> list = new ArrayList<>(diaries.size());
        for (EmotionDiary d : diaries) {
            User u = userMap.get(d.getUserId());
            list.add(EmotionDiaryVO.builder()
                    .id(d.getId())
                    .userId(d.getUserId())
                    .username(u == null ? null : u.getUsername())
                    .nickname(u == null ? null : u.getNickname())
                    .diaryDate(d.getDiaryDate())
                    .moodScore(d.getMoodScore())
                    .dominantEmotion(d.getDominantEmotion())
                    .emotionTriggers(d.getEmotionTriggers())
                    .diaryContent(d.getDiaryContent())
                    .sleepQuality(d.getSleepQuality())
                    .stressLevel(d.getStressLevel())
                    .aiEmotionAnalysis(d.getAiEmotionAnalysis())
                    .aiAnalysisUpdatedAt(d.getAiAnalysisUpdatedAt())
                    .createdAt(d.getCreatedAt())
                    .updatedAt(d.getUpdatedAt())
                    .build());
        }
        return list;
    }

    /**
     * 调用规则引擎生成分析 JSON。
     *
     * @param dto 日记表单
     * @return JSON 字符串；无文本可分析或分析异常时返回 null
     */
    private String buildAnalysisJson(EmotionDiaryCreateDTO dto) {
        String text = StrUtil.join(" ",
                StrUtil.nullToEmpty(dto.getDominantEmotion()),
                StrUtil.nullToEmpty(dto.getEmotionTriggers()),
                StrUtil.nullToEmpty(dto.getDiaryContent()));
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            EmotionAnalyzer.EmotionResult result = EmotionAnalyzer.analyze(text);
            // 字段名与管理端 emotional.vue 的 aiData 读取保持一致
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("primaryEmotion", result.primaryEmotion());
            data.put("emotionScore", result.emotionScore());
            data.put("isNegative", result.isNegative());
            data.put("riskLevel", result.riskLevel());
            data.put("riskDescription", EmotionAnalyzer.riskDescription(result.riskLevel()));
            data.put("suggestion", result.suggestion());
            data.put("improvementSuggestions", result.improvementSuggestions());
            data.put("matchedKeywords", result.matchedKeywords());
            return JSONUtil.toJsonStr(data);
        } catch (Exception e) {
            // 分析属于增强能力，失败不影响日记本身落库
            log.warn("情绪日记 AI 分析失败，已跳过分析结果", e);
            return null;
        }
    }
}
