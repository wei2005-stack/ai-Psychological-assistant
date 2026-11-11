package org.example.aispingboot.controller;

import jakarta.validation.Valid;
import org.example.aispingboot.DTO.command.KnowledgeArticleSaveDTO;
import org.example.aispingboot.DTO.command.KnowledgeArticleStatusDTO;
import org.example.aispingboot.DTO.response.KnowledgeArticleVO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.entity.KnowledgeCategory;
import org.example.aispingboot.service.KnowledgeArticleService;
import org.example.aispingboot.util.CurrentUserUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识文章接口。
 *
 * <p>路由权限说明：</p>
 * <ul>
 *   <li>GET /article/page、GET /article/{id} 在 SecurityConfig 中以「仅 GET」方式放行，
 *       游客也能浏览知识库；</li>
 *   <li>其余写操作（新增/编辑/上下架/删除）和分类树都需要登录，
 *       且在方法内再校验 roleType=2（管理员），防止普通用户越权管理内容。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeArticleController {

    @Autowired
    private KnowledgeArticleService knowledgeArticleService;

    /**
     * 分类列表，供管理端文章编辑下拉使用。
     *
     * @return 启用状态的分类
     */
    @GetMapping("/category/tree")
    public Result<List<KnowledgeCategory>> categoryTree() {
        return Result.ok(knowledgeArticleService.listCategories());
    }

    /**
     * 分页查询文章。
     *
     * <p>前端参数：currentPage、size、title、categoryId、status、sortField、sortDirection。
     * 游客 / 普通用户只能拿到已发布文章，管理员可看全部。</p>
     *
     * @return { records, total, currentPage, size }
     */
    @GetMapping("/article/page")
    public Result<Map<String, Object>> articlePage(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection) {
        // 该接口是公开 GET，过滤器被跳过，这里直接从请求头 token 判断是否管理员
        boolean admin = CurrentUserUtil.isAdmin();
        return Result.ok(knowledgeArticleService.page(currentPage, size, title, categoryId,
                status, sortField, sortDirection, admin));
    }

    /**
     * 文章详情，同时累加阅读量。
     *
     * @param id 文章 ID
     * @return 文章详情
     */
    @GetMapping("/article/{id}")
    public Result<KnowledgeArticleVO> detail(@PathVariable String id) {
        KnowledgeArticleVO vo = knowledgeArticleService.detail(id, CurrentUserUtil.isAdmin());
        if (vo == null) {
            return Result.error(ResultCode.PARAM_ERROR.getCode(), "文章不存在或未发布", null);
        }
        return Result.ok(vo);
    }

    /**
     * 新增文章（管理员）。
     *
     * @param dto 文章内容
     * @return 新文章 ID
     */
    @PostMapping("/article")
    public Result<String> create(@Valid @RequestBody KnowledgeArticleSaveDTO dto) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(knowledgeArticleService.create(dto, CurrentUserUtil.currentUserId()));
    }

    /**
     * 编辑文章（管理员）。
     *
     * @param id  文章 ID
     * @param dto 文章内容
     * @return 操作结果
     */
    @PutMapping("/article/{id}")
    public Result<Void> update(@PathVariable String id,
                               @Valid @RequestBody KnowledgeArticleSaveDTO dto) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        knowledgeArticleService.update(id, dto);
        return Result.ok();
    }

    /**
     * 发布 / 下线文章（管理员）。
     *
     * @param id  文章 ID
     * @param dto 目标状态
     * @return 操作结果
     */
    @PutMapping("/article/{id}/status")
    public Result<Void> changeStatus(@PathVariable String id,
                                     @Valid @RequestBody KnowledgeArticleStatusDTO dto) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        knowledgeArticleService.changeStatus(id, dto.getStatus());
        return Result.ok();
    }

    /**
     * 删除文章（管理员）。
     *
     * @param id 文章 ID
     * @return 操作结果
     */
    @DeleteMapping("/article/{id}")
    public Result<Void> delete(@PathVariable String id) {
        if (!CurrentUserUtil.isAdmin()) {
            return Result.error(ResultCode.ACCESS_UNAUTHORIZED.getCode(),
                    ResultCode.ACCESS_UNAUTHORIZED.getMsg(), null);
        }
        if (!knowledgeArticleService.delete(id)) {
            return Result.error(ResultCode.PARAM_ERROR.getCode(), "文章不存在", null);
        }
        return Result.ok();
    }
}
