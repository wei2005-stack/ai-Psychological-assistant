package org.example.aispingboot.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.aispingboot.DTO.command.KnowledgeArticleSaveDTO;
import org.example.aispingboot.DTO.response.KnowledgeArticleVO;
import org.example.aispingboot.entity.KnowledgeArticle;
import org.example.aispingboot.entity.KnowledgeCategory;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.exception.BusinessException;
import org.example.aispingboot.mapper.KnowledgeArticleMapper;
import org.example.aispingboot.mapper.KnowledgeCategoryMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识文章业务服务。
 *
 * <p>同时服务前台（知识库列表、文章详情）与管理端（文章增删改查、上下架）：
 * </p>
 * <ul>
 *   <li>前台只能看到 status=1（已发布）的文章，管理端可以按状态筛选；</li>
 *   <li>列表需要 categoryName / authorName，这里统一批量补齐，避免 N+1 查询；</li>
 *   <li>文章主键是 UUID 字符串，新增时前端可能已经生成（上传封面用的 businessId），
 *       优先复用，未传则后端生成。</li>
 * </ul>
 */
@Service
public class KnowledgeArticleService {

    /** 状态：草稿 */
    private static final int STATUS_DRAFT = 0;
    /** 状态：已发布 */
    private static final int STATUS_PUBLISHED = 1;

    @Autowired
    private KnowledgeArticleMapper articleMapper;

    @Autowired
    private KnowledgeCategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    /**
     * 查询启用状态的分类列表（平铺）。
     * 管理端 ArticleDialog 的下拉选项直接使用 id / categoryName。
     *
     * @return 分类列表，按 sort_order、id 升序
     */
    public List<KnowledgeCategory> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<KnowledgeCategory>()
                .eq(KnowledgeCategory::getStatus, 1)
                .orderByAsc(KnowledgeCategory::getSortOrder)
                .orderByAsc(KnowledgeCategory::getId));
    }

    /**
     * 分页查询文章。
     *
     * @param currentPage   页码，从 1 开始
     * @param size          每页条数
     * @param title         标题模糊搜索，可为 null
     * @param categoryId    分类筛选，可为 null
     * @param status        状态筛选，可为 null
     * @param sortField     排序字段：publishedAt / readCount / updatedAt / createdAt
     * @param sortDirection 排序方向：asc / desc
     * @param admin         当前调用者是否管理员；非管理员强制只看已发布
     * @return { records, total, currentPage, size }
     */
    public Map<String, Object> page(int currentPage, int size, String title, Long categoryId,
                                    Integer status, String sortField, String sortDirection, boolean admin) {
        LambdaQueryWrapper<KnowledgeArticle> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(title)) {
            wrapper.like(KnowledgeArticle::getTitle, title.trim());
        }
        if (categoryId != null) {
            wrapper.eq(KnowledgeArticle::getCategoryId, categoryId);
        }
        if (!admin) {
            // 游客和普通用户永远只能看到已发布文章，忽略前端传入的 status
            wrapper.eq(KnowledgeArticle::getStatus, STATUS_PUBLISHED);
        } else if (status != null) {
            wrapper.eq(KnowledgeArticle::getStatus, status);
        }

        // 排序字段走白名单，避免把前端参数直接拼进 SQL
        boolean asc = "asc".equalsIgnoreCase(sortDirection);
        switch (StrUtil.blankToDefault(sortField, "publishedAt")) {
            case "readCount" -> {
                if (asc) {
                    wrapper.orderByAsc(KnowledgeArticle::getReadCount);
                } else {
                    wrapper.orderByDesc(KnowledgeArticle::getReadCount);
                }
            }
            case "updatedAt" -> {
                if (asc) {
                    wrapper.orderByAsc(KnowledgeArticle::getUpdatedAt);
                } else {
                    wrapper.orderByDesc(KnowledgeArticle::getUpdatedAt);
                }
            }
            case "createdAt" -> {
                if (asc) {
                    wrapper.orderByAsc(KnowledgeArticle::getCreatedAt);
                } else {
                    wrapper.orderByDesc(KnowledgeArticle::getCreatedAt);
                }
            }
            default -> {
                if (asc) {
                    wrapper.orderByAsc(KnowledgeArticle::getPublishedAt);
                } else {
                    wrapper.orderByDesc(KnowledgeArticle::getPublishedAt);
                }
            }
        }
        wrapper.orderByDesc(KnowledgeArticle::getId);

        long total = articleMapper.selectCount(wrapper);
        int offset = Math.max(0, (currentPage - 1) * size);
        wrapper.last("limit " + offset + "," + size);
        List<KnowledgeArticle> articles = articleMapper.selectList(wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", toVOList(articles));
        result.put("total", total);
        result.put("currentPage", currentPage);
        result.put("size", size);
        return result;
    }

    /**
     * 查询文章详情。
     *
     * @param id    文章 ID
     * @param admin 是否管理员；非管理员不能查看草稿/已下线文章
     * @return 文章详情，不存在或无权查看时返回 null
     */
    public KnowledgeArticleVO detail(String id, boolean admin) {
        KnowledgeArticle article = articleMapper.selectById(id);
        if (article == null) {
            return null;
        }
        // 非管理员访问未发布文章，统一按「不存在」处理，避免暴露草稿内容
        if (!admin && !Integer.valueOf(STATUS_PUBLISHED).equals(article.getStatus())) {
            return null;
        }
        // 前台阅读才累加阅读量；管理端打开编辑弹窗不应污染统计
        if (!admin) {
            articleMapper.update(null, new LambdaUpdateWrapper<KnowledgeArticle>()
                    .eq(KnowledgeArticle::getId, id)
                    .setSql("read_count = read_count + 1"));
        }
        KnowledgeArticleVO vo = toVOList(List.of(article)).get(0);
        int readCount = article.getReadCount() == null ? 0 : article.getReadCount();
        vo.setReadCount(admin ? readCount : readCount + 1);
        return vo;
    }

    /**
     * 新增文章（管理端）。
     *
     * @param dto      文章内容
     * @param authorId 当前管理员 ID
     * @return 新文章 ID
     */
    public String create(KnowledgeArticleSaveDTO dto, Long authorId) {
        ensureCategoryExists(dto.getCategoryId());
        KnowledgeArticle article = KnowledgeArticle.builder()
                // 前端上传封面时生成的 businessId 会作为 id 传上来，为空则后端生成
                .id(StrUtil.isBlank(dto.getId()) ? UUID.randomUUID().toString() : dto.getId())
                .categoryId(dto.getCategoryId())
                .title(dto.getTitle())
                .summary(dto.getSummary())
                .content(dto.getContent())
                .coverImage(dto.getCoverImage())
                .tags(dto.getTags())
                .authorId(authorId)
                .readCount(0)
                // 新建默认草稿，由管理端「发布」按钮改成已发布
                .status(STATUS_DRAFT)
                .build();
        articleMapper.insert(article);
        return article.getId();
    }

    /**
     * 编辑文章（管理端）。
     *
     * @param id  文章 ID
     * @param dto 文章内容
     */
    public void update(String id, KnowledgeArticleSaveDTO dto) {
        KnowledgeArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        ensureCategoryExists(dto.getCategoryId());
        article.setCategoryId(dto.getCategoryId());
        article.setTitle(dto.getTitle());
        article.setSummary(dto.getSummary());
        article.setContent(dto.getContent());
        article.setCoverImage(dto.getCoverImage());
        article.setTags(dto.getTags());
        articleMapper.updateById(article);
    }

    /**
     * 修改文章状态（发布 / 下线）。
     * 首次发布时写入 published_at，供前台按发布时间排序。
     *
     * @param id     文章 ID
     * @param status 目标状态 0/1/2
     */
    public void changeStatus(String id, Integer status) {
        KnowledgeArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        article.setStatus(status);
        if (status == STATUS_PUBLISHED && article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }
        articleMapper.updateById(article);
    }

    /**
     * 删除文章（管理端）。
     *
     * @param id 文章 ID
     * @return true 表示删除成功
     */
    public boolean delete(String id) {
        return articleMapper.deleteById(id) > 0;
    }

    /**
     * 校验分类是否存在且启用，避免外键错误直接抛 SQL 异常给前端。
     *
     * @param categoryId 分类 ID
     */
    private void ensureCategoryExists(Long categoryId) {
        KnowledgeCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !Integer.valueOf(1).equals(category.getStatus())) {
            throw new BusinessException("分类不存在或已禁用");
        }
    }

    /**
     * 实体转视图对象，并批量补齐分类名与作者名。
     *
     * @param articles 文章实体列表
     * @return VO 列表
     */
    private List<KnowledgeArticleVO> toVOList(List<KnowledgeArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> categoryIds = articles.stream()
                .map(KnowledgeArticle::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(KnowledgeCategory::getId,
                                c -> StrUtil.nullToEmpty(c.getCategoryName()), (a, b) -> a));

        Set<Long> authorIds = articles.stream()
                .map(KnowledgeArticle::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> authors = authorIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(authorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<KnowledgeArticleVO> list = new ArrayList<>(articles.size());
        for (KnowledgeArticle a : articles) {
            User author = authors.get(a.getAuthorId());
            list.add(KnowledgeArticleVO.builder()
                    .id(a.getId())
                    .categoryId(a.getCategoryId())
                    .categoryName(categoryNames.get(a.getCategoryId()))
                    .title(a.getTitle())
                    .summary(a.getSummary())
                    .content(a.getContent())
                    .coverImage(a.getCoverImage())
                    .tags(a.getTags())
                    // 前端详情页用 v-for 渲染 tagArray，这里按逗号拆开并去掉空白项
                    .tagArray(splitTags(a.getTags()))
                    .authorId(a.getAuthorId())
                    .authorName(author == null ? null : author.getDisplayName())
                    .readCount(a.getReadCount() == null ? 0 : a.getReadCount())
                    .status(a.getStatus())
                    .publishedAt(a.getPublishedAt())
                    .createdAt(a.getCreatedAt())
                    .updatedAt(a.getUpdatedAt())
                    .build());
        }
        return list;
    }

    /**
     * 把英文逗号分隔的标签串拆成数组。
     *
     * @param tags 标签串
     * @return 标签数组，空串返回空列表
     */
    private List<String> splitTags(String tags) {
        if (StrUtil.isBlank(tags)) {
            return Collections.emptyList();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }
}
