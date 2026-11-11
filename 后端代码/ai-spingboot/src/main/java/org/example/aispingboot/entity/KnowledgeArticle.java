package org.example.aispingboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文章实体，对应 knowledge_article 表。
 *
 * <p>主键是 varchar(36) 的 UUID，由业务层生成（前端上传封面时也用 UUID 作为 businessId），
 * 因此 {@code @TableId} 使用 {@link IdType#INPUT}，插入时不再让数据库自增。</p>
 */
@Data
@TableName("knowledge_article")
@Builder
public class KnowledgeArticle {

    /** 文章 ID（UUID，业务层生成） */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属分类 ID */
    @TableField("category_id")
    private Long categoryId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文（富文本 HTML） */
    private String content;

    /** 封面图片相对路径 */
    @TableField("cover_image")
    private String coverImage;

    /** 标签，英文逗号分隔 */
    private String tags;

    /** 作者 ID */
    @TableField("author_id")
    private Long authorId;

    /** 阅读量 */
    @TableField("read_count")
    private Integer readCount;

    /** 状态：0 草稿 / 1 已发布 / 2 已下线 */
    private Integer status;

    /** 发布时间，只有发布后才写入 */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
