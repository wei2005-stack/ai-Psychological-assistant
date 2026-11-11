package org.example.aispingboot.DTO.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文章视图对象。
 *
 * <p>同时服务前台列表/详情与管理端表格：列表只用到 title、categoryName、authorName、
 * coverImage、readCount、updatedAt、status 等字段；详情额外需要 content、summary、tagArray。</p>
 */
@Data
@Builder
public class KnowledgeArticleVO {

    /** 文章 ID（UUID） */
    private String id;

    /** 分类 ID */
    private Long categoryId;

    /** 分类名称（由分类表补充） */
    private String categoryName;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文（富文本 HTML） */
    private String content;

    /** 封面图片相对路径 */
    private String coverImage;

    /** 标签原始字符串（英文逗号分隔） */
    private String tags;

    /** 标签数组：详情页 articleDetail.vue 用 v-for 渲染，由 tags 拆分而来 */
    private List<String> tagArray;

    /** 作者 ID */
    private Long authorId;

    /** 作者名称（优先昵称，其次用户名） */
    private String authorName;

    /** 阅读量 */
    private Integer readCount;

    /** 状态：0 草稿 / 1 已发布 / 2 已下线 */
    private Integer status;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
