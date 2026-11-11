package org.example.aispingboot.DTO.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文章上下架参数。
 * 管理端「发布 / 下线」按钮只提交一个 status 字段。
 */
@Data
public class KnowledgeArticleStatusDTO {

    /** 目标状态：0 草稿 / 1 已发布 / 2 已下线 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0/1/2")
    @Max(value = 2, message = "状态只能是 0/1/2")
    private Integer status;
}
