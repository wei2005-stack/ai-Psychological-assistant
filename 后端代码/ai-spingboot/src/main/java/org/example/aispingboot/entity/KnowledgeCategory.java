package org.example.aispingboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文章分类实体，对应 knowledge_category 表。
 *
 * <p>表里虽然有 parent_id，但当前前端只需要一层的下拉选项，
 * 因此接口直接返回启用状态的平铺列表（按 sort_order、id 排序）。</p>
 */
@Data
@TableName("knowledge_category")
@Builder
public class KnowledgeCategory {

    /** 分类 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分类 ID，0 表示顶级分类 */
    @TableField("parent_id")
    private Long parentId;

    /** 分类名称 */
    @TableField("category_name")
    private String categoryName;

    /** 分类编码（唯一） */
    @TableField("category_code")
    private String categoryCode;

    /** 分类描述 */
    private String description;

    /** 排序值，越小越靠前 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 状态：0 禁用 / 1 启用 */
    private Integer status;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
