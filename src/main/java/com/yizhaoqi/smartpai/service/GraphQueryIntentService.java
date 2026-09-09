package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GraphQueryIntentService {

    private static final Logger logger = LoggerFactory.getLogger(GraphQueryIntentService.class);
    private static final String NO_ORG_TAG_MATCH = "__NO_ORG_TAG_MATCH__";
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9《》“”\"()（）·\\-]{2,60}");
    private static final int MAX_ENTITY_TERMS = 10;
    private static final int ENTITY_SEARCH_LIMIT = 5;

    private final RagEntityRepository entityRepository;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;

    public GraphQueryIntentService(RagEntityRepository entityRepository,
                                   UserRepository userRepository,
                                   OrgTagCacheService orgTagCacheService) {
        this.entityRepository = entityRepository;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
    }

    public GraphQueryIntent classify(String query, String userId, boolean graphSearchEnabled) {
        long startedAt = System.nanoTime();
        if (!graphSearchEnabled) {
            return GraphQueryIntent.disabled(elapsedMs(startedAt));
        }
        if (!StringUtils.hasText(query)) {
            return GraphQueryIntent.fallback("问题为空，不调用 Graph", elapsedMs(startedAt));
        }

        try {
            RuleDecision ruleDecision = classifyByRules(query);
            if (ruleDecision.definitive()) {
                return new GraphQueryIntent(
                        ruleDecision.queryType(),
                        ruleDecision.needsGraph(),
                        ruleDecision.confidence(),
                        List.of(),
                        ruleDecision.reason(),
                        GraphQueryIntent.RouteSource.RULES,
                        elapsedMs(startedAt),
                        false,
                        0
                );
            }

            GraphQueryIntent entityIntent = classifyByEntityLinking(query, userId, ruleDecision, startedAt);
            if (entityIntent != null) {
                return entityIntent;
            }

            return new GraphQueryIntent(
                    ruleDecision.queryType(),
                    false,
                    ruleDecision.confidence(),
                    List.of(),
                    ruleDecision.reason(),
                    GraphQueryIntent.RouteSource.FALLBACK,
                    elapsedMs(startedAt),
                    false,
                    0
            );
        } catch (Exception e) {
            logger.warn("Graph 意图路由失败，回退为不调用 Graph: userId={}, query={}", userId, query, e);
            return GraphQueryIntent.fallback("路由失败，回退普通知识库检索", elapsedMs(startedAt));
        }
    }

    RuleDecision classifyByRules(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return new RuleDecision(false, false, GraphQueryIntent.QueryType.UNKNOWN, 0.4d, "问题为空");
        }
        if (looksLikeKnowledgeBypass(normalized)) {
            return new RuleDecision(true, false, GraphQueryIntent.QueryType.UNKNOWN, 0.9d, "命中无需知识库的白名单，不做 Graph 路由");
        }

        if (containsAny(normalized, "对比", "比较", "差异", "不同", "区别", "异同")) {
            return new RuleDecision(true, true, GraphQueryIntent.QueryType.COMPARISON, 0.9d, "命中对比/差异类 Graph 规则");
        }
        if (containsAny(normalized, "共同规则", "共同要求", "共同条件", "汇总", "归纳", "分别", "各自", "哪些文件", "哪些制度")) {
            return new RuleDecision(true, true, GraphQueryIntent.QueryType.AGGREGATION, 0.88d, "命中聚合/共同规则类 Graph 规则");
        }
        if (containsAny(normalized, "关系", "关联", "联系", "影响", "依赖", "前置", "后续", "先后", "之间", "同时")) {
            return new RuleDecision(true, true, GraphQueryIntent.QueryType.RELATIONSHIP, 0.86d, "命中关系/依赖类 Graph 规则");
        }
        if (containsAny(normalized, "流程", "步骤", "条件链", "满足哪些条件", "哪些条件", "申请条件", "适用对象", "适用范围", "适用于")) {
            return new RuleDecision(true, true, GraphQueryIntent.QueryType.MULTI_HOP, 0.84d, "命中流程/条件/适用范围类 Graph 规则");
        }

        if (looksLikeSimpleFact(normalized)) {
            return new RuleDecision(true, false, GraphQueryIntent.QueryType.FACTUAL, 0.82d, "命中单点事实类规则");
        }

        if (hasSoftGraphTendency(normalized)) {
            return new RuleDecision(false, false, GraphQueryIntent.QueryType.UNKNOWN, 0.58d, "存在弱关系倾向，继续做实体链接检查");
        }
        return new RuleDecision(false, false, GraphQueryIntent.QueryType.UNKNOWN, 0.5d, "未命中明确 Graph 规则");
    }

    private GraphQueryIntent classifyByEntityLinking(String query,
                                                     String userId,
                                                     RuleDecision ruleDecision,
                                                     long startedAt) {
        if (!hasSoftGraphTendency(normalize(query))) {
            return null;
        }

        PermissionScope scope = resolvePermissionScope(userId);
        Set<String> matchedEntityNames = new LinkedHashSet<>();
        for (String term : extractCandidateTerms(query)) {
            List<RagEntity> entities = entityRepository.searchAccessibleEntities(
                    term,
                    normalizeEntity(term),
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, ENTITY_SEARCH_LIMIT)
            );
            for (RagEntity entity : entities) {
                if (entity != null && StringUtils.hasText(entity.getName())) {
                    matchedEntityNames.add(entity.getName());
                }
            }
            if (matchedEntityNames.size() >= ENTITY_SEARCH_LIMIT) {
                break;
            }
        }

        if (matchedEntityNames.isEmpty()) {
            return null;
        }
        return new GraphQueryIntent(
                inferSoftQueryType(query),
                true,
                Math.max(ruleDecision.confidence(), 0.72d),
                matchedEntityNames.stream().toList(),
                "问题存在弱关系倾向，且命中可访问图谱实体",
                GraphQueryIntent.RouteSource.ENTITY_LINKING,
                elapsedMs(startedAt),
                false,
                0
        );
    }

    private GraphQueryIntent.QueryType inferSoftQueryType(String query) {
        String normalized = normalize(query);
        if (containsAny(normalized, "和", "与", "同", "一起", "同时", "以及")) {
            return GraphQueryIntent.QueryType.RELATIONSHIP;
        }
        if (containsAny(normalized, "哪些", "所有", "汇总", "归纳")) {
            return GraphQueryIntent.QueryType.AGGREGATION;
        }
        return GraphQueryIntent.QueryType.UNKNOWN;
    }

    private boolean looksLikeSimpleFact(String normalizedQuery) {
        boolean factSignal = containsAny(normalizedQuery,
                "金额", "多少钱", "多少元", "是多少", "什么时候", "时间", "日期", "地点", "地址",
                "人数", "名额", "电话", "邮箱", "定义", "是什么", "第几", "几分", "多少学分");
        if (!factSignal) {
            return false;
        }
        return !containsAny(normalizedQuery,
                "关系", "关联", "联系", "影响", "依赖", "对比", "比较", "差异", "不同",
                "流程", "条件", "适用", "之间", "同时", "共同");
    }

    private boolean looksLikeKnowledgeBypass(String normalizedQuery) {
        if (containsAny(normalizedQuery, "不要查知识库", "不用查知识库", "直接回答", "无需检索")) {
            return true;
        }
        if (normalizedQuery.matches("^(你好|您好|谢谢|感谢|再见|拜拜)[！!。,.，]*$")) {
            return true;
        }
        if (containsAny(normalizedQuery, "写诗", "写段子", "编故事", "翻译成", "把") && containsAny(normalizedQuery, "翻译", "写诗", "写段子", "编故事")) {
            return true;
        }
        return normalizedQuery.matches("^[0-9+\\-*/×÷().（）=\\s]+$");
    }

    private boolean hasSoftGraphTendency(String normalizedQuery) {
        return containsAny(normalizedQuery, "和", "与", "及", "以及", "同时", "共同", "一起", "相关", "涉及", "哪些", "如何");
    }

    private PermissionScope resolvePermissionScope(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        User user;
        String userDbId;
        try {
            Long parsedId = Long.parseLong(userId);
            user = userRepository.findById(parsedId)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            userDbId = parsedId.toString();
        } catch (NumberFormatException e) {
            user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            userDbId = user.getId().toString();
        }

        List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        List<String> orgTags = effectiveTags == null || effectiveTags.isEmpty()
                ? List.of(NO_ORG_TAG_MATCH)
                : effectiveTags.stream().filter(StringUtils::hasText).distinct().toList();
        return new PermissionScope(userDbId, orgTags.isEmpty() ? List.of(NO_ORG_TAG_MATCH) : orgTags);
    }

    private List<String> extractCandidateTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String cleaned = cleanText(query);
        if (StringUtils.hasText(cleaned) && cleaned.length() <= 60) {
            terms.add(cleaned);
        }

        Matcher matcher = TERM_PATTERN.matcher(query);
        while (matcher.find() && terms.size() < MAX_ENTITY_TERMS) {
            String term = cleanText(matcher.group());
            if (term.length() >= 2) {
                terms.add(term);
            }
        }

        List<String> suffixes = List.of("政策", "办法", "条例", "规定", "通知", "方案", "计划", "指南", "制度",
                "专业", "学院", "课程", "奖学金", "助学金", "资格", "申请", "评阅", "答辩");
        for (String suffix : suffixes) {
            int index = query.indexOf(suffix);
            if (index >= 1) {
                int from = Math.max(0, index - 28);
                terms.add(cleanText(query.substring(from, index + suffix.length())));
            }
        }
        return new ArrayList<>(terms).stream()
                .filter(StringUtils::hasText)
                .limit(MAX_ENTITY_TERMS)
                .toList();
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("^[《“\"\\s]+", "")
                .replaceAll("[》”\"\\s]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String normalizeEntity(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[《》“”\"'`（）()\\[\\]{}]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    record RuleDecision(boolean definitive,
                        boolean needsGraph,
                        GraphQueryIntent.QueryType queryType,
                        double confidence,
                        String reason) {
    }

    private record PermissionScope(String userDbId, List<String> orgTags) {
    }
}
