package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.RagEntityMention;
import com.yizhaoqi.smartpai.model.RagRelation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GraphSearchService {

    private static final Logger logger = LoggerFactory.getLogger(GraphSearchService.class);
    private static final String NO_ORG_TAG_MATCH = "__NO_ORG_TAG_MATCH__";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SEARCH_CANDIDATES = 80;
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9《》“”\"()（）·\\-]{2,60}");

    private final RagEntityRepository entityRepository;
    private final RagEntityMentionRepository mentionRepository;
    private final RagRelationRepository relationRepository;
    private final RagCrossDocumentRelationRepository crossDocumentRelationRepository;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;

    public GraphSearchService(RagEntityRepository entityRepository,
                              RagEntityMentionRepository mentionRepository,
                              RagRelationRepository relationRepository,
                              RagCrossDocumentRelationRepository crossDocumentRelationRepository,
                              UserRepository userRepository,
                              OrgTagCacheService orgTagCacheService,
                              FileUploadRepository fileUploadRepository) {
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.relationRepository = relationRepository;
        this.crossDocumentRelationRepository = crossDocumentRelationRepository;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
    }

    @Transactional(readOnly = true)
    public List<GraphEntitySummary> findEntitySummaryByFileMd5(String fileMd5, String userId) {
        validateText(fileMd5, "fileMd5 must not be blank");
        PermissionScope scope = resolvePermissionScope(userId);
        return summarizeMentions(mentionRepository.findByFileMd5WithPermission(fileMd5, scope.userDbId(), scope.orgTags()));
    }

    @Transactional(readOnly = true)
    public List<GraphRelationSummary> findRelationSummaryByFileMd5(String fileMd5, String userId) {
        validateText(fileMd5, "fileMd5 must not be blank");
        PermissionScope scope = resolvePermissionScope(userId);
        List<GraphRelationSummary> summaries = new ArrayList<>();
        relationRepository.findByFileMd5WithPermission(fileMd5, scope.userDbId(), scope.orgTags()).stream()
                .map(this::toRelationSummary)
                .forEach(summaries::add);
        crossDocumentRelationRepository.findByFileMd5WithPermission(fileMd5, scope.userDbId(), scope.orgTags()).stream()
                .map(this::toRelationSummary)
                .forEach(summaries::add);
        return summaries;
    }

    @Transactional(readOnly = true)
    public List<GraphEntitySummary> searchEntities(String keyword, String userId, int limit) {
        validateText(keyword, "keyword must not be blank");
        PermissionScope scope = resolvePermissionScope(userId);
        int safeLimit = normalizeLimit(limit);
        List<RagEntity> entities = entityRepository.searchAccessibleEntities(
                keyword.trim(),
                normalizeText(keyword),
                scope.userDbId(),
                scope.orgTags(),
                PageRequest.of(0, safeLimit)
        );
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> entityIds = entities.stream().map(RagEntity::getId).toList();
        return summarizeMentions(mentionRepository.findByEntityIdsWithPermission(
                entityIds,
                scope.userDbId(),
                scope.orgTags(),
                PageRequest.of(0, Math.min(safeLimit * 5, MAX_SEARCH_CANDIDATES))
        )).stream()
                .limit(safeLimit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GraphNeighborRelation> findNeighbors(Long entityId, String userId) {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
        PermissionScope scope = resolvePermissionScope(userId);
        List<GraphNeighborRelation> neighbors = new ArrayList<>();
        relationRepository.findByEntityIdWithPermission(entityId, scope.userDbId(), scope.orgTags()).stream()
                .map(relation -> toNeighborRelation(relation, entityId))
                .forEach(neighbors::add);
        crossDocumentRelationRepository.findByEntityIdWithPermission(entityId, scope.userDbId(), scope.orgTags()).stream()
                .map(relation -> toNeighborRelation(relation, entityId))
                .forEach(neighbors::add);
        return neighbors;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(String query, String userId, int topK) {
        validateText(query, "query must not be blank");
        PermissionScope scope = resolvePermissionScope(userId);
        int safeTopK = normalizeLimit(topK);

        Set<Long> entityIds = new LinkedHashSet<>();
        for (String term : extractCandidateTerms(query)) {
            List<RagEntity> matched = entityRepository.searchAccessibleEntities(
                    term,
                    normalizeText(term),
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, 20)
            );
            matched.forEach(entity -> entityIds.add(entity.getId()));
            if (entityIds.size() >= MAX_SEARCH_CANDIDATES) {
                break;
            }
        }

        Map<String, GraphCandidate> candidates = new LinkedHashMap<>();
        if (!entityIds.isEmpty()) {
            List<RagEntityMention> mentions = mentionRepository.findByEntityIdsWithPermission(
                    entityIds,
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, MAX_SEARCH_CANDIDATES)
            );
            mentions.forEach(mention -> addMentionCandidate(candidates, mention, query, true));

            List<RagRelation> relations = relationRepository.findByAnyEntityIdsWithPermission(
                    entityIds,
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, MAX_SEARCH_CANDIDATES)
            );
            relations.forEach(relation -> addRelationCandidate(candidates, relation, query, true));
        }

        mentionRepository.searchEvidenceWithPermission(query.trim(), scope.userDbId(), scope.orgTags(), PageRequest.of(0, MAX_SEARCH_CANDIDATES))
                .forEach(mention -> addMentionCandidate(candidates, mention, query, false));
        relationRepository.searchEvidenceWithPermission(query.trim(), scope.userDbId(), scope.orgTags(), PageRequest.of(0, MAX_SEARCH_CANDIDATES))
                .forEach(relation -> addRelationCandidate(candidates, relation, query, false));

        List<SearchResult> results = candidates.values().stream()
                .sorted(Comparator.comparingDouble(GraphCandidate::score).reversed())
                .limit(safeTopK)
                .map(GraphCandidate::toSearchResult)
                .toList();
        attachFileNames(results);
        return results;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        try {
            List<SearchResult> localResults = localSearch(query, userId, topK).sourceChunks();
            if (!localResults.isEmpty()) {
                return localResults;
            }
        } catch (Exception e) {
            logger.warn("Graph localSearch 失败，回退旧 Graph 搜索: userId={}, query={}", userId, query, e);
        }
        try {
            return search(query, userId, topK);
        } catch (Exception e) {
            logger.warn("旧 Graph 搜索也失败，返回空 Graph 结果: userId={}, query={}", userId, query, e);
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public GraphLocalSearchResult localSearch(String query, String userId, int topK) {
        validateText(query, "query must not be blank");
        PermissionScope scope = resolvePermissionScope(userId);
        int safeTopK = normalizeLimit(topK);
        Set<Long> entityIds = resolveEntityIds(query, scope);

        Map<String, GraphCandidate> candidates = new LinkedHashMap<>();
        Map<Long, GraphNodeAccumulator> nodes = new LinkedHashMap<>();
        Map<String, GraphEdgeEvidence> edges = new LinkedHashMap<>();

        if (!entityIds.isEmpty()) {
            mentionRepository.findByEntityIdsWithPermission(
                    entityIds,
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, MAX_SEARCH_CANDIDATES)
            ).forEach(mention -> {
                addMentionCandidate(candidates, mention, query, true);
                addNodeEvidence(nodes, mention);
            });

            relationRepository.findByAnyEntityIdsWithPermission(
                    entityIds,
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, MAX_SEARCH_CANDIDATES)
            ).forEach(relation -> {
                addRelationCandidate(candidates, relation, query, true);
                addEdgeEvidence(edges, relation);
            });

            crossDocumentRelationRepository.findByAnyEntityIdsWithPermission(
                    entityIds,
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, MAX_SEARCH_CANDIDATES)
            ).forEach(relation -> {
                addCrossDocumentRelationCandidates(candidates, relation, query, true);
                addEdgeEvidence(edges, relation);
                addNodeEvidence(nodes, relation.getSourceEntity(), relation.getSourceFileMd5(), relation.getSourceChunkId(),
                        relation.getSourcePageNumber(), relation.getSourceAnchorText(), relation.getSourceEvidenceText());
                addNodeEvidence(nodes, relation.getTargetEntity(), relation.getTargetFileMd5(), relation.getTargetChunkId(),
                        relation.getTargetPageNumber(), relation.getTargetAnchorText(), relation.getTargetEvidenceText());
            });
        }

        mentionRepository.searchEvidenceWithPermission(query.trim(), scope.userDbId(), scope.orgTags(), PageRequest.of(0, MAX_SEARCH_CANDIDATES))
                .forEach(mention -> {
                    addMentionCandidate(candidates, mention, query, false);
                    addNodeEvidence(nodes, mention);
                });
        relationRepository.searchEvidenceWithPermission(query.trim(), scope.userDbId(), scope.orgTags(), PageRequest.of(0, MAX_SEARCH_CANDIDATES))
                .forEach(relation -> {
                    addRelationCandidate(candidates, relation, query, false);
                    addEdgeEvidence(edges, relation);
                });
        crossDocumentRelationRepository.searchEvidenceWithPermission(query.trim(), scope.userDbId(), scope.orgTags(), PageRequest.of(0, MAX_SEARCH_CANDIDATES))
                .forEach(relation -> {
                    addCrossDocumentRelationCandidates(candidates, relation, query, false);
                    addEdgeEvidence(edges, relation);
                    addNodeEvidence(nodes, relation.getSourceEntity(), relation.getSourceFileMd5(), relation.getSourceChunkId(),
                            relation.getSourcePageNumber(), relation.getSourceAnchorText(), relation.getSourceEvidenceText());
                    addNodeEvidence(nodes, relation.getTargetEntity(), relation.getTargetFileMd5(), relation.getTargetChunkId(),
                            relation.getTargetPageNumber(), relation.getTargetAnchorText(), relation.getTargetEvidenceText());
                });

        List<SearchResult> sourceChunks = candidates.values().stream()
                .sorted(Comparator.comparingDouble(GraphCandidate::score).reversed())
                .limit(safeTopK)
                .map(GraphCandidate::toSearchResult)
                .toList();
        attachFileNames(sourceChunks);
        return new GraphLocalSearchResult(
                nodes.values().stream().map(GraphNodeAccumulator::toEvidence).toList(),
                new ArrayList<>(edges.values()),
                sourceChunks
        );
    }

    private void addMentionCandidate(Map<String, GraphCandidate> candidates, RagEntityMention mention, String query, boolean directEntityHit) {
        GraphCandidate candidate = candidates.computeIfAbsent(chunkKey(mention.getFileMd5(), mention.getChunkId()),
                key -> GraphCandidate.fromMention(mention));
        double score = directEntityHit ? 5.0d : 1.0d;
        if (containsNormalized(mention.getEntity().getName(), query) || containsNormalized(mention.getEntity().getNormalizedName(), query)) {
            score += 2.0d;
        }
        if (containsNormalized(mention.getEvidenceText(), query) || containsNormalized(mention.getAnchorText(), query)) {
            score += 1.5d;
        }
        candidate.addScore(score);
        candidate.addEvidence(mention.getEvidenceText());
    }

    private void addRelationCandidate(Map<String, GraphCandidate> candidates, RagRelation relation, String query, boolean directEntityHit) {
        GraphCandidate candidate = candidates.computeIfAbsent(chunkKey(relation.getFileMd5(), relation.getChunkId()),
                key -> GraphCandidate.fromRelation(relation));
        double score = directEntityHit ? 3.0d : 1.5d;
        score += relationTypeWeight(relation.getRelationType());
        if (containsNormalized(relation.getEvidenceText(), query) || containsNormalized(relation.getAnchorText(), query)) {
            score += 2.0d;
        }
        if (containsNormalized(relation.getRelationType(), query)) {
            score += 1.0d;
        }
        candidate.addScore(score);
        candidate.addEvidence(relation.getEvidenceText());
    }

    private void addCrossDocumentRelationCandidates(Map<String, GraphCandidate> candidates,
                                                    RagCrossDocumentRelation relation,
                                                    String query,
                                                    boolean directEntityHit) {
        double score = directEntityHit ? 4.5d : 2.0d;
        score += relationTypeWeight(relation.getRelationType());
        score += relation.getConfidence();
        if (containsNormalized(relation.getEvidenceText(), query)
                || containsNormalized(relation.getRelationDescription(), query)
                || containsNormalized(relation.getRelationType(), query)) {
            score += 2.0d;
        }

        GraphCandidate sourceCandidate = candidates.computeIfAbsent(
                chunkKey(relation.getSourceFileMd5(), relation.getSourceChunkId()),
                key -> GraphCandidate.fromCrossDocumentSource(relation)
        );
        sourceCandidate.addScore(score);
        sourceCandidate.addEvidence(relation.getSourceEvidenceText());

        GraphCandidate targetCandidate = candidates.computeIfAbsent(
                chunkKey(relation.getTargetFileMd5(), relation.getTargetChunkId()),
                key -> GraphCandidate.fromCrossDocumentTarget(relation)
        );
        targetCandidate.addScore(score * 0.95d);
        targetCandidate.addEvidence(relation.getTargetEvidenceText());
    }

    private Set<Long> resolveEntityIds(String query, PermissionScope scope) {
        Set<Long> entityIds = new LinkedHashSet<>();
        for (String term : extractCandidateTerms(query)) {
            List<RagEntity> matched = entityRepository.searchAccessibleEntities(
                    term,
                    normalizeText(term),
                    scope.userDbId(),
                    scope.orgTags(),
                    PageRequest.of(0, 20)
            );
            matched.forEach(entity -> entityIds.add(entity.getId()));
            if (entityIds.size() >= MAX_SEARCH_CANDIDATES) {
                break;
            }
        }
        return entityIds;
    }

    private void addNodeEvidence(Map<Long, GraphNodeAccumulator> nodes, RagEntityMention mention) {
        addNodeEvidence(
                nodes,
                mention.getEntity(),
                mention.getFileMd5(),
                mention.getChunkId(),
                mention.getPageNumber(),
                mention.getAnchorText(),
                mention.getEvidenceText()
        );
    }

    private void addNodeEvidence(Map<Long, GraphNodeAccumulator> nodes,
                                 RagEntity entity,
                                 String fileMd5,
                                 Integer chunkId,
                                 Integer pageNumber,
                                 String anchorText,
                                 String evidenceText) {
        nodes.computeIfAbsent(entity.getId(), id -> new GraphNodeAccumulator(entity))
                .add(fileMd5, chunkId, pageNumber, anchorText, evidenceText);
    }

    private void addEdgeEvidence(Map<String, GraphEdgeEvidence> edges, RagRelation relation) {
        String key = "relation:" + relation.getId();
        edges.putIfAbsent(key, new GraphEdgeEvidence(
                relation.getId(),
                "INTRA_DOCUMENT",
                relation.getSourceEntity().getId(),
                relation.getSourceEntity().getName(),
                relation.getTargetEntity().getId(),
                relation.getTargetEntity().getName(),
                relation.getRelationType(),
                null,
                1.0d,
                "RULES",
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText(),
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText()
        ));
    }

    private void addEdgeEvidence(Map<String, GraphEdgeEvidence> edges, RagCrossDocumentRelation relation) {
        String key = "cross:" + relation.getId();
        edges.putIfAbsent(key, new GraphEdgeEvidence(
                relation.getId(),
                "CROSS_DOCUMENT",
                relation.getSourceEntity().getId(),
                relation.getSourceEntity().getName(),
                relation.getTargetEntity().getId(),
                relation.getTargetEntity().getName(),
                relation.getRelationType(),
                relation.getRelationDescription(),
                relation.getConfidence(),
                relation.getBuildMethod(),
                relation.getSourceFileMd5(),
                relation.getSourceChunkId(),
                relation.getSourcePageNumber(),
                relation.getSourceAnchorText(),
                relation.getSourceEvidenceText(),
                relation.getTargetFileMd5(),
                relation.getTargetChunkId(),
                relation.getTargetPageNumber(),
                relation.getTargetAnchorText(),
                relation.getTargetEvidenceText()
        ));
    }

    private List<GraphEntitySummary> summarizeMentions(List<RagEntityMention> mentions) {
        Map<Long, EntitySummaryAccumulator> grouped = new LinkedHashMap<>();
        for (RagEntityMention mention : mentions) {
            grouped.computeIfAbsent(mention.getEntity().getId(), id -> new EntitySummaryAccumulator(mention))
                    .add(mention);
        }
        return grouped.values().stream()
                .map(EntitySummaryAccumulator::toSummary)
                .toList();
    }

    private GraphRelationSummary toRelationSummary(RagRelation relation) {
        return new GraphRelationSummary(
                relation.getId(),
                relation.getSourceEntity().getId(),
                relation.getSourceEntity().getName(),
                relation.getSourceEntity().getType(),
                relation.getTargetEntity().getId(),
                relation.getTargetEntity().getName(),
                relation.getTargetEntity().getType(),
                relation.getRelationType(),
                "INTRA_DOCUMENT",
                null,
                1.0d,
                "RULES",
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText(),
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText(),
                relation.getUserId(),
                relation.getOrgTag(),
                relation.isPublic()
        );
    }

    private GraphRelationSummary toRelationSummary(RagCrossDocumentRelation relation) {
        return new GraphRelationSummary(
                relation.getId(),
                relation.getSourceEntity().getId(),
                relation.getSourceEntity().getName(),
                relation.getSourceEntity().getType(),
                relation.getTargetEntity().getId(),
                relation.getTargetEntity().getName(),
                relation.getTargetEntity().getType(),
                relation.getRelationType(),
                "CROSS_DOCUMENT",
                relation.getRelationDescription(),
                relation.getConfidence(),
                relation.getBuildMethod(),
                relation.getSourceFileMd5(),
                relation.getSourceChunkId(),
                relation.getSourcePageNumber(),
                relation.getSourceAnchorText(),
                relation.getSourceEvidenceText(),
                relation.getTargetFileMd5(),
                relation.getTargetChunkId(),
                relation.getTargetPageNumber(),
                relation.getTargetAnchorText(),
                relation.getTargetEvidenceText(),
                relation.getSourceUserId(),
                relation.getSourceOrgTag(),
                relation.isSourcePublic()
        );
    }

    private GraphNeighborRelation toNeighborRelation(RagRelation relation, Long entityId) {
        RagEntity neighbor = relation.getSourceEntity().getId().equals(entityId)
                ? relation.getTargetEntity()
                : relation.getSourceEntity();
        String direction = relation.getSourceEntity().getId().equals(entityId) ? "OUT" : "IN";
        return new GraphNeighborRelation(
                relation.getId(),
                direction,
                neighbor.getId(),
                neighbor.getName(),
                neighbor.getType(),
                relation.getRelationType(),
                "INTRA_DOCUMENT",
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText(),
                relation.getFileMd5(),
                relation.getChunkId(),
                relation.getPageNumber(),
                relation.getAnchorText(),
                relation.getEvidenceText(),
                relation.getUserId(),
                relation.getOrgTag(),
                relation.isPublic()
        );
    }

    private GraphNeighborRelation toNeighborRelation(RagCrossDocumentRelation relation, Long entityId) {
        RagEntity neighbor = relation.getSourceEntity().getId().equals(entityId)
                ? relation.getTargetEntity()
                : relation.getSourceEntity();
        String direction = relation.getSourceEntity().getId().equals(entityId) ? "OUT" : "IN";
        return new GraphNeighborRelation(
                relation.getId(),
                direction,
                neighbor.getId(),
                neighbor.getName(),
                neighbor.getType(),
                relation.getRelationType(),
                "CROSS_DOCUMENT",
                relation.getSourceFileMd5(),
                relation.getSourceChunkId(),
                relation.getSourcePageNumber(),
                relation.getSourceAnchorText(),
                relation.getSourceEvidenceText(),
                relation.getTargetFileMd5(),
                relation.getTargetChunkId(),
                relation.getTargetPageNumber(),
                relation.getTargetAnchorText(),
                relation.getTargetEvidenceText(),
                relation.getSourceUserId(),
                relation.getSourceOrgTag(),
                relation.isSourcePublic()
        );
    }

    private PermissionScope resolvePermissionScope(String userId) {
        validateText(userId, "userId must not be blank");
        try {
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
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("解析 Graph 查询权限失败: userId={}", userId, e);
            throw new RuntimeException("解析 Graph 查询权限失败", e);
        }
    }

    private List<String> extractCandidateTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String cleaned = cleanText(query);
        if (StringUtils.hasText(cleaned) && cleaned.length() <= 60) {
            terms.add(cleaned);
        }

        Matcher matcher = TERM_PATTERN.matcher(query);
        while (matcher.find() && terms.size() < 12) {
            String term = cleanText(matcher.group());
            if (term.length() >= 2) {
                terms.add(term);
            }
        }

        String[] suffixes = {"政策", "办法", "条例", "规定", "通知", "方案", "计划", "指南", "制度", "专业", "学院", "课程", "奖学金", "助学金"};
        for (String suffix : suffixes) {
            int index = query.indexOf(suffix);
            if (index >= 1) {
                int from = Math.max(0, index - 28);
                terms.add(cleanText(query.substring(from, index + suffix.length())));
            }
        }

        return terms.stream()
                .filter(StringUtils::hasText)
                .limit(12)
                .toList();
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
            if (md5Set.isEmpty()) {
                return;
            }
            Map<String, String> md5ToName = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set)).stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            results.forEach(result -> result.setFileName(md5ToName.get(result.getFileMd5())));
        } catch (Exception e) {
            logger.warn("Graph 查询补充文件名失败", e);
        }
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static void validateText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String chunkKey(String fileMd5, Integer chunkId) {
        return fileMd5 + ":" + chunkId;
    }

    private static double relationTypeWeight(String relationType) {
        if ("requires".equals(relationType)) {
            return 1.5d;
        }
        if ("applies_to".equals(relationType)) {
            return 1.4d;
        }
        if ("contains".equals(relationType)) {
            return 1.2d;
        }
        if ("co_occurs".equals(relationType)) {
            return 0.8d;
        }
        if (RagCrossDocumentRelation.TYPE_SAME_ENTITY.equals(relationType)) {
            return 1.8d;
        }
        return 1.0d;
    }

    private static boolean containsNormalized(String value, String query) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(query)) {
            return false;
        }
        String normalizedValue = normalizeText(value);
        String normalizedQuery = normalizeText(query);
        return normalizedValue.contains(normalizedQuery) || normalizedQuery.contains(normalizedValue);
    }

    private static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("^[《“\"\\s]+", "")
                .replaceAll("[》”\"\\s]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[《》“”\"'`（）()\\[\\]{}]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private record PermissionScope(String userDbId, List<String> orgTags) {
    }

    private static class EntitySummaryAccumulator {
        private final RagEntity entity;
        private String fileMd5;
        private Integer chunkId;
        private Integer pageNumber;
        private String anchorText;
        private String evidenceText;
        private String userId;
        private String orgTag;
        private boolean isPublic;
        private int mentionCount;

        EntitySummaryAccumulator(RagEntityMention mention) {
            this.entity = mention.getEntity();
            this.fileMd5 = mention.getFileMd5();
            this.chunkId = mention.getChunkId();
            this.pageNumber = mention.getPageNumber();
            this.anchorText = mention.getAnchorText();
            this.evidenceText = mention.getEvidenceText();
            this.userId = mention.getUserId();
            this.orgTag = mention.getOrgTag();
            this.isPublic = mention.isPublic();
        }

        void add(RagEntityMention mention) {
            mentionCount++;
            if (!StringUtils.hasText(evidenceText) && StringUtils.hasText(mention.getEvidenceText())) {
                evidenceText = mention.getEvidenceText();
                fileMd5 = mention.getFileMd5();
                chunkId = mention.getChunkId();
                pageNumber = mention.getPageNumber();
                anchorText = mention.getAnchorText();
                userId = mention.getUserId();
                orgTag = mention.getOrgTag();
                isPublic = mention.isPublic();
            }
        }

        GraphEntitySummary toSummary() {
            return new GraphEntitySummary(
                    entity.getId(),
                    entity.getName(),
                    entity.getNormalizedName(),
                    entity.getType(),
                    fileMd5,
                    chunkId,
                    pageNumber,
                    anchorText,
                    evidenceText,
                    mentionCount,
                    userId,
                    orgTag,
                    isPublic
            );
        }
    }

    private static class GraphCandidate {
        private final String fileMd5;
        private final Integer chunkId;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private final Integer pageNumber;
        private final String anchorText;
        private double score;
        private String evidenceText;

        private GraphCandidate(String fileMd5,
                               Integer chunkId,
                               String userId,
                               String orgTag,
                               boolean isPublic,
                               Integer pageNumber,
                               String anchorText,
                               String evidenceText) {
            this.fileMd5 = fileMd5;
            this.chunkId = chunkId;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
            this.pageNumber = pageNumber;
            this.anchorText = anchorText;
            this.evidenceText = evidenceText;
        }

        static GraphCandidate fromMention(RagEntityMention mention) {
            return new GraphCandidate(
                    mention.getFileMd5(),
                    mention.getChunkId(),
                    mention.getUserId(),
                    mention.getOrgTag(),
                    mention.isPublic(),
                    mention.getPageNumber(),
                    mention.getAnchorText(),
                    mention.getEvidenceText()
            );
        }

        static GraphCandidate fromRelation(RagRelation relation) {
            return new GraphCandidate(
                    relation.getFileMd5(),
                    relation.getChunkId(),
                    relation.getUserId(),
                    relation.getOrgTag(),
                    relation.isPublic(),
                    relation.getPageNumber(),
                    relation.getAnchorText(),
                    relation.getEvidenceText()
            );
        }

        static GraphCandidate fromCrossDocumentSource(RagCrossDocumentRelation relation) {
            return new GraphCandidate(
                    relation.getSourceFileMd5(),
                    relation.getSourceChunkId(),
                    relation.getSourceUserId(),
                    relation.getSourceOrgTag(),
                    relation.isSourcePublic(),
                    relation.getSourcePageNumber(),
                    relation.getSourceAnchorText(),
                    relation.getSourceEvidenceText()
            );
        }

        static GraphCandidate fromCrossDocumentTarget(RagCrossDocumentRelation relation) {
            return new GraphCandidate(
                    relation.getTargetFileMd5(),
                    relation.getTargetChunkId(),
                    relation.getTargetUserId(),
                    relation.getTargetOrgTag(),
                    relation.isTargetPublic(),
                    relation.getTargetPageNumber(),
                    relation.getTargetAnchorText(),
                    relation.getTargetEvidenceText()
            );
        }

        void addScore(double value) {
            this.score += value;
        }

        void addEvidence(String value) {
            if (!StringUtils.hasText(evidenceText) && StringUtils.hasText(value)) {
                evidenceText = value;
            }
        }

        double score() {
            return score;
        }

        SearchResult toSearchResult() {
            String content = StringUtils.hasText(evidenceText) ? evidenceText : anchorText;
            return new SearchResult(
                    fileMd5,
                    chunkId,
                    content,
                    score,
                    userId,
                    orgTag,
                    isPublic,
                    null,
                    pageNumber,
                    anchorText,
                    "GRAPH",
                    content
            );
        }
    }

    private static class GraphNodeAccumulator {
        private final RagEntity entity;
        private final Set<String> fileMd5s = new LinkedHashSet<>();
        private Integer chunkId;
        private Integer pageNumber;
        private String anchorText;
        private String evidenceText;
        private int mentionCount;

        GraphNodeAccumulator(RagEntity entity) {
            this.entity = entity;
        }

        void add(String fileMd5, Integer chunkId, Integer pageNumber, String anchorText, String evidenceText) {
            if (StringUtils.hasText(fileMd5)) {
                fileMd5s.add(fileMd5);
            }
            mentionCount++;
            if (!StringUtils.hasText(this.evidenceText) && StringUtils.hasText(evidenceText)) {
                this.chunkId = chunkId;
                this.pageNumber = pageNumber;
                this.anchorText = anchorText;
                this.evidenceText = evidenceText;
            }
        }

        GraphNodeEvidence toEvidence() {
            return new GraphNodeEvidence(
                    entity.getId(),
                    entity.getName(),
                    entity.getNormalizedName(),
                    entity.getType(),
                    fileMd5s.size(),
                    mentionCount,
                    fileMd5s.stream().toList(),
                    chunkId,
                    pageNumber,
                    anchorText,
                    evidenceText
            );
        }
    }

    public record GraphLocalSearchResult(List<GraphNodeEvidence> nodes,
                                         List<GraphEdgeEvidence> edges,
                                         List<SearchResult> sourceChunks) {
    }

    public record GraphNodeEvidence(Long entityId,
                                    String name,
                                    String normalizedName,
                                    String type,
                                    int sourceFileCount,
                                    int mentionCount,
                                    List<String> sourceFileMd5s,
                                    Integer chunkId,
                                    Integer pageNumber,
                                    String anchorText,
                                    String evidenceText) {
    }

    public record GraphEdgeEvidence(Long relationId,
                                    String relationScope,
                                    Long sourceEntityId,
                                    String sourceName,
                                    Long targetEntityId,
                                    String targetName,
                                    String relationType,
                                    String relationDescription,
                                    double confidence,
                                    String buildMethod,
                                    String sourceFileMd5,
                                    Integer sourceChunkId,
                                    Integer sourcePageNumber,
                                    String sourceAnchorText,
                                    String sourceEvidenceText,
                                    String targetFileMd5,
                                    Integer targetChunkId,
                                    Integer targetPageNumber,
                                    String targetAnchorText,
                                    String targetEvidenceText) {
    }

    public record GraphEntitySummary(Long entityId,
                                     String name,
                                     String normalizedName,
                                     String type,
                                     String fileMd5,
                                     Integer chunkId,
                                     Integer pageNumber,
                                     String anchorText,
                                     String evidenceText,
                                     int mentionCount,
                                     String userId,
                                     String orgTag,
                                     boolean isPublic) {
    }

    public record GraphRelationSummary(Long relationId,
                                       Long sourceEntityId,
                                       String sourceName,
                                       String sourceType,
                                       Long targetEntityId,
                                       String targetName,
                                       String targetType,
                                       String relationType,
                                       String relationScope,
                                       String relationDescription,
                                       double confidence,
                                       String buildMethod,
                                       String sourceFileMd5,
                                       Integer sourceChunkId,
                                       Integer sourcePageNumber,
                                       String sourceAnchorText,
                                       String sourceEvidenceText,
                                       String targetFileMd5,
                                       Integer targetChunkId,
                                       Integer targetPageNumber,
                                       String targetAnchorText,
                                       String targetEvidenceText,
                                       String userId,
                                       String orgTag,
                                       boolean isPublic) {
    }

    public record GraphNeighborRelation(Long relationId,
                                        String direction,
                                        Long neighborEntityId,
                                        String neighborName,
                                        String neighborType,
                                        String relationType,
                                        String relationScope,
                                        String sourceFileMd5,
                                        Integer sourceChunkId,
                                        Integer sourcePageNumber,
                                        String sourceAnchorText,
                                        String sourceEvidenceText,
                                        String targetFileMd5,
                                        Integer targetChunkId,
                                        Integer targetPageNumber,
                                        String targetAnchorText,
                                        String targetEvidenceText,
                                        String userId,
                                        String orgTag,
                                        boolean isPublic) {
    }
}
