package org.example.aispingboot.DTO.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识文章新增/编辑参数，对应管理端 ArticleDialog.vue 的提交体。
 *
 * <p>前端提交时会把标签数组 `tagArray` 用英文逗号拼成 `tags` 字符串再发送，
 * 因此这里只需要一个 String 字段；返回详情时再拆回数组。</p>
 */
@Data
public class KnowledgeArticleSaveDTO {

    /** 文章 ID：新增时前端可能用 UUID 作为 businessId，允许为空由后端生成 */
    @Size(max = 36, message = "文章 ID 长度不能超过 36 个字符")
    private String id;

    /** 所属分类 ID，前端必选 */
    @NotNull(message = "所属分类不能为空")
    private Long categoryId;

    /** 标题 */
    @NotBlank(message = "文章标题不能为空")
    @Size(max = 200, message = "文章标题最多 200 个字符")
    private String title;

    /** 摘要，可选 */
    @Size(max = 1000, message = "文章摘要最多 1000 个字符")
    private String summary;

    /** 正文（富文本 HTML） */
    @NotBlank(message = "文章内容不能为空")
    @Size(max = 20000, message = "文章内容最多 20000 个字符")
    private String content;

    /** 封面图片相对路径，可选 */
    @Size(max = 500, message = "封面路径最多 500 个字符")
    private String coverImage;

    /** 标签，英文逗号分隔 */
    @Size(max = 500, message = "标签最多 500 个字符")
    private String tags;
}
