package org.example.aispingboot.controller;

import jakarta.validation.Valid;
import org.example.aispingboot.DTO.command.EmotionDiaryCreateDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.service.EmotionDiaryService;
import org.example.aispingboot.util.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 情绪日记接口。
 *
 * <p>前台：提交当日日记（同一用户同一天重复提交会覆盖）；<br>
 * 管理端：分页查看所有用户日记、删除日记。</p>
 */
@RestController
@RequestMapping("/api/emotion-diary")
public class EmotionDiaryController {

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    /**
     * 提交当日情绪日记。
     *
     * @param dto 前端 emotionDiary.vue 的表单
     * @return 保存后的日记
     */
    @PostMapping
    public Result<EmotionDiary> submit(@Valid @RequestBody EmotionDiaryCreateDTO dto) {
        // 该接口不在公开白名单内，JWT 过滤器已保证请求带合法 token
        Long userId = CurrentUserUtil.currentUserId();
        return Result.ok(emotionDiaryService.saveOrUpdate(userId, dto));
    }

    /**
     * 管理端分页查询情绪日记。
     *
     * @param current        页码，从 1 开始（前端 emotional.vue 的字段名是 current）
     * @param size           每页条数
     * @param userId         按用户 ID 筛选
     * @param moodScreRange  评分区间，如 1-3（保持与前端字段名一致）
     * @return { records, total, current, size }
     */
    @GetMapping("/admin/page")
    public Result<Map<String, Object>> adminPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, name = "moodScreRange") String moodScreRange) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(emotionDiaryService.adminPage(current, size, userId, moodScreRange));
    }

    /**
     * 管理端删除某条日记。
     *
     * @param id 日记 ID
     * @return 删除结果
     */
    @DeleteMapping("/admin/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        if (!emotionDiaryService.delete(id)) {
            return Result.error(ResultCode.PARAM_ERROR.getCode(), "日记不存在", null);
        }
        return Result.ok();
    }
}
