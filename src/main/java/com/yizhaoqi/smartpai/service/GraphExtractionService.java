package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.RagEntityMention;
import com.yizhaoqi.smartpai.model.RagRelation;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GraphExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(GraphExtractionService.class);

    private static final int MAX_MENTIONS_PER_CHUNK = 40;
    private static final int MAX_CO_OCCUR_MENTIONS_PER_CHUNK = 16;
    private static final int MAX_EVIDENCE_LENGTH = 260;
    private static final int MAX_CROSS_LINK_FILES = 80;
    private static final int MAX_CROSS_TARGET_MENTIONS = 600;
    private static final int MAX_CROSS_DOCUMENT_RELATIONS_PER_FILE = 300;

    private static final List<EntityPattern> ENTITY_PATTERNS = List.of(
            new EntityPattern("policy", compile("([\\p{IsHan}A-Za-z0-9《》“”\"()（）·\\-]{2,40}(?:政策|办法|条例|规定|通知|方案|计划|指南|章程|细则|制度))"), 1),
            new EntityPattern("major", compile("([\\p{IsHan}A-Za-z0-9·\\-]{2,30}(?:专业|学科|方向|学院|系))"), 1),
            new EntityPattern("award", compile("([\\p{IsHan}A-Za-z0-9·\\-]{0,24}(?:奖学金|助学金|奖项|补助|奖励|grant|scholarship))"), 1),
            new EntityPattern("course", compile("([\\p{IsHan}A-Za-z0-9·\\-]{2,30}(?:课程|必修课|选修课|通识课|实验课))"), 1),
            new EntityPattern("condition", compile("((?:满足|符合|具备|达到|须|需|必须|应当|要求|条件)[^。；;.!?！？\\n]{1,60})"), 1),
            new EntityPattern("amount", compile("((?:\\d+(?:\\.\\d+)?\\s*(?:元|万元|美元|人民币|%|％))|(?:[一二三四五六七八九十百千万]+(?:元|万元|美元|人民币)))"), 1),
            new EntityPattern("time", compile("((?:\\d{4}[年/-]\\d{1,2}(?:[月/-]\\d{1,2}日?)?)|(?:\\d{1,2}月\\d{1,2}日)|(?:截止(?:时间|日期)?[^。；;.!?！？\\n]{0,20})|(?:有效期[^。；;.!?！？\\n]{0,20}))"), 1),
            new EntityPattern("gpa", compile("((?:GPA|gpa|绩点|平均分)[：: ]?\\d+(?:\\.\\d+)?)"), 1),
            new EntityPattern("credit", compile("((?:\\d+(?:\\.\\d+)?\\s*学分)|(?:学分[：: ]?\\d+(?:\\.\\d+)?))"), 1),
            new EntityPattern("general", compile("(?:《([^》]{2,50})》)|(?:“([^”]{2,50})”)|(?:\"([^\"]{2,50})\")"), 1, 2, 3)
    );

    private final DocumentVectorRepository documentVectorRepository;
    private final RagEntityRepository entityRepository;
    private final RagEntityMentionRepository mentionRepository;
    private final RagRelationRepository relationRepository;
    private final RagCrossDocumentRelationRepository crossDocumentRelationRepository;
    private final GraphDocumentStateService graphDocumentStateService;

    public GraphExtractionService(DocumentVectorRepository documentVectorRepository,
                                  RagEntityRepository entityRepository,
                                  RagEntityMentionRepository mentionRepository,
                                  RagRelationRepository relationRepository,
                                  RagCrossDocumentRelationRepository crossDocumentRelationRepository,
                                  GraphDocumentStateService graphDocumentStateService) {
        this.documentVectorRepository = documentVectorRepository;
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.relationRepository = relationRepository;
        this.crossDocumentRelationRepository = crossDocumentRelationRepository;
        this.graphDocumentStateService = graphDocumentStateService;
    }

    @Transactional
    public GraphExtractionResult rebuildForFile(String fileMd5, String userId, String orgTag, boolean isPublic) {
        if (!StringUtils.hasText(fileMd5)) {
            throw new IllegalArgumentException("fileMd5 must not be blank");
        }
        long startedAt = System.nanoTime();

        GraphDocumentStateService.GraphFileContentCleanupResult cleanupResult =
                graphDocumentStateService.deleteGraphContentByFile(fileMd5);
        int deletedRelations = cleanupResult.deletedRelationCount();
        int deletedMentions = cleanupResult.deletedMentionCount();
        int deletedCrossDocumentRelations = cleanupResult.deletedCrossDocumentRelationCount();

        List<DocumentVector> chunks = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);
        if (chunks.isEmpty()) {
            logger.info("图谱抽取跳过，未找到文档切片: fileMd5={}, deletedMentions={}, deletedRelations={}",
                    fileMd5, deletedMentions, deletedRelations);
            return new GraphExtractionResult(
                    0,
                    0,
                    0,
                    0,
                    0,
                    deletedMentions,
                    deletedRelations,
                    deletedCrossDocumentRelations,
                    elapsedMs(startedAt)
            );
        }

        Map<String, RagEntity> entityCache = new LinkedHashMap<>();
        List<RagEntityMention> mentions = new ArrayList<>();
        List<RagRelation> relations = new ArrayList<>();
        Set<String> mentionKeys = new HashSet<>();
        Set<String> relationKeys = new HashSet<>();

        for (DocumentVector chunk : chunks) {
            List<MentionContext> chunkMentions = extractMentions(chunk, userId, orgTag, isPublic, entityCache, mentionKeys);
            mentions.addAll(chunkMentions.stream().map(MentionContext::mention).toList());
            relations.addAll(extractRelations(chunk, chunkMentions, userId, orgTag, isPublic, relationKeys));
        }

        if (!mentions.isEmpty()) {
            mentionRepository.saveAll(mentions);
        }
        if (!relations.isEmpty()) {
            relationRepository.saveAll(relations);
        }
        List<RagCrossDocumentRelation> crossDocumentRelations = extractCrossDocumentRelations(fileMd5, mentions);
        if (!crossDocumentRelations.isEmpty()) {
            crossDocumentRelationRepository.saveAll(crossDocumentRelations);
        }

        long buildMs = elapsedMs(startedAt);
        int totalRelations = relations.size() + crossDocumentRelations.size();
        logger.info("图谱抽取完成: fileMd5={}, chunks={}, entities={}, mentions={}, relations={}, crossDocumentRelations={}, deletedMentions={}, deletedRelations={}, deletedCrossDocumentRelations={}, buildMs={}",
                fileMd5,
                chunks.size(),
                entityCache.size(),
                mentions.size(),
                totalRelations,
                crossDocumentRelations.size(),
                deletedMentions,
                deletedRelations,
                deletedCrossDocumentRelations,
                buildMs);
        return new GraphExtractionResult(
                chunks.size(),
                entityCache.size(),
                mentions.size(),
                totalRelations,
                crossDocumentRelations.size(),
                deletedMentions,
                deletedRelations,
                deletedCrossDocumentRelations,
                buildMs
        );
    }

    private List<MentionContext> extractMentions(DocumentVector chunk,
                                                String userId,
                                                String orgTag,
                                                boolean isPublic,
                                                Map<String, RagEntity> entityCache,
                                                Set<String> mentionKeys) {
        String content = chunk.getTextContent();
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        Map<String, CandidateMention> candidates = new LinkedHashMap<>();
        for (EntityPattern entityPattern : ENTITY_PATTERNS) {
            Matcher matcher = entityPattern.pattern().matcher(content);
            while (matcher.find() && candidates.size() < MAX_MENTIONS_PER_CHUNK) {
                MatchText matchText = entityPattern.matchText(matcher);
                if (matchText == null || !StringUtils.hasText(matchText.text())) {
                    continue;
                }
                String name = cleanEntityName(matchText.text());
                String normalizedName = normalizeEntityName(name);
                if (!isValidEntityName(normalizedName)) {
                    continue;
                }

                String key = entityPattern.type() + "|" + normalizedName;
                candidates.putIfAbsent(key, new CandidateMention(
                        name,
                        normalizedName,
                        entityPattern.type(),
                        getEvidenceWindow(content, matchText.start(), matchText.end())
                ));
            }
        }

        List<MentionContext> contexts = new ArrayList<>();
        for (CandidateMention candidate : candidates.values()) {
            RagEntity entity = resolveEntity(candidate, entityCache);
            String mentionKey = chunk.getFileMd5() + "|" + chunk.getChunkId() + "|" + entity.getId();
            if (!mentionKeys.add(mentionKey)) {
                continue;
            }

            RagEntityMention mention = new RagEntityMention();
            mention.setEntity(entity);
            mention.setFileMd5(chunk.getFileMd5());
            mention.setChunkId(chunk.getChunkId());
            mention.setPageNumber(chunk.getPageNumber());
            mention.setAnchorText(firstText(chunk.getAnchorText(), candidate.name()));
            mention.setEvidenceText(candidate.evidenceText());
            mention.setUserId(userId);
            mention.setOrgTag(orgTag);
            mention.setPublic(isPublic);
            contexts.add(new MentionContext(candidate, entity, mention));
        }
        return contexts;
    }

    private List<RagRelation> extractRelations(DocumentVector chunk,
                                               List<MentionContext> chunkMentions,
                                               String userId,
                                               String orgTag,
                                               boolean isPublic,
                                               Set<String> relationKeys) {
        if (chunkMentions.size() < 2) {
            return List.of();
        }

        List<RagRelation> relations = new ArrayList<>();
        List<MentionContext> coOccurs = chunkMentions.stream()
                .limit(MAX_CO_OCCUR_MENTIONS_PER_CHUNK)
                .toList();

        for (int i = 0; i < coOccurs.size(); i++) {
            for (int j = i + 1; j < coOccurs.size(); j++) {
                addRelation(relations, relationKeys, chunk, coOccurs.get(i), coOccurs.get(j), "co_occurs",
                        firstText(coOccurs.get(i).candidate().evidenceText(), coOccurs.get(j).candidate().evidenceText()),
                        userId, orgTag, isPublic);
            }
        }

        for (String sentence : splitSentences(chunk.getTextContent())) {
            List<MentionContext> sentenceMentions = chunkMentions.stream()
                    .filter(context -> sentence.contains(context.candidate().name()))
                    .toList();
            if (sentenceMentions.size() < 2) {
                continue;
            }

            addContainsRelations(relations, relationKeys, chunk, sentenceMentions, sentence, userId, orgTag, isPublic);
            if (containsAny(sentence, "要求", "条件", "须", "需", "必须", "应当", "达到", "满足", "符合", "资格")) {
                addRequiresRelations(relations, relationKeys, chunk, sentenceMentions, sentence, userId, orgTag, isPublic);
            }
            if (containsAny(sentence, "适用于", "适用", "面向", "对象", "申请", "可报考", "招生", "报名")) {
                addAppliesToRelations(relations, relationKeys, chunk, sentenceMentions, sentence, userId, orgTag, isPublic);
            }
        }

        return relations;
    }

    private void addContainsRelations(List<RagRelation> relations,
                                      Set<String> relationKeys,
                                      DocumentVector chunk,
                                      List<MentionContext> sentenceMentions,
                                      String sentence,
                                      String userId,
                                      String orgTag,
                                      boolean isPublic) {
        List<MentionContext> containers = sentenceMentions.stream()
                .filter(context -> isContainerType(context.candidate().type()))
                .toList();
        if (containers.isEmpty()) {
            return;
        }
        for (MentionContext source : containers) {
            for (MentionContext target : sentenceMentions) {
                addRelation(relations, relationKeys, chunk, source, target, "contains", sentence, userId, orgTag, isPublic);
            }
        }
    }

    private void addRequiresRelations(List<RagRelation> relations,
                                      Set<String> relationKeys,
                                      DocumentVector chunk,
                                      List<MentionContext> sentenceMentions,
                                      String sentence,
                                      String userId,
                                      String orgTag,
                                      boolean isPublic) {
        List<MentionContext> subjects = sentenceMentions.stream()
                .filter(context -> isSubjectType(context.candidate().type()))
                .toList();
        List<MentionContext> targets = sentenceMentions.stream()
                .filter(context -> isRequirementTargetType(context.candidate().type()))
                .toList();
        for (MentionContext source : subjects) {
            for (MentionContext target : targets) {
                addRelation(relations, relationKeys, chunk, source, target, "requires", sentence, userId, orgTag, isPublic);
            }
        }
    }

    private void addAppliesToRelations(List<RagRelation> relations,
                                       Set<String> relationKeys,
                                       DocumentVector chunk,
                                       List<MentionContext> sentenceMentions,
                                       String sentence,
                                       String userId,
                                       String orgTag,
                                       boolean isPublic) {
        List<MentionContext> subjects = sentenceMentions.stream()
                .filter(context -> isSubjectType(context.candidate().type()))
                .toList();
        List<MentionContext> targets = sentenceMentions.stream()
                .filter(context -> "major".equals(context.candidate().type()) || "general".equals(context.candidate().type()) || "condition".equals(context.candidate().type()))
                .toList();
        for (MentionContext source : subjects) {
            for (MentionContext target : targets) {
                addRelation(relations, relationKeys, chunk, source, target, "applies_to", sentence, userId, orgTag, isPublic);
            }
        }
    }

    private void addRelation(List<RagRelation> relations,
                             Set<String> relationKeys,
                             DocumentVector chunk,
                             MentionContext source,
                             MentionContext target,
                             String relationType,
                             String evidenceText,
                             String userId,
                             String orgTag,
                             boolean isPublic) {
        if (source.entity().getId().equals(target.entity().getId())) {
            return;
        }

        MentionContext normalizedSource = source;
        MentionContext normalizedTarget = target;
        if ("co_occurs".equals(relationType) && relationKeyPart(target).compareTo(relationKeyPart(source)) < 0) {
            normalizedSource = target;
            normalizedTarget = source;
        }

        String relationKey = chunk.getFileMd5() + "|" + chunk.getChunkId() + "|" + normalizedSource.entity().getId()
                + "|" + normalizedTarget.entity().getId() + "|" + relationType;
        if (!relationKeys.add(relationKey)) {
            return;
        }

        RagRelation relation = new RagRelation();
        relation.setSourceEntity(normalizedSource.entity());
        relation.setTargetEntity(normalizedTarget.entity());
        relation.setRelationType(relationType);
        relation.setFileMd5(chunk.getFileMd5());
        relation.setChunkId(chunk.getChunkId());
        relation.setPageNumber(chunk.getPageNumber());
        relation.setAnchorText(firstText(chunk.getAnchorText(), normalizedSource.candidate().name()));
        relation.setEvidenceText(limitLength(cleanEvidence(evidenceText), MAX_EVIDENCE_LENGTH));
        relation.setUserId(userId);
        relation.setOrgTag(orgTag);
        relation.setPublic(isPublic);
        relations.add(relation);
    }

    private List<RagCrossDocumentRelation> extractCrossDocumentRelations(String sourceFileMd5,
                                                                         List<RagEntityMention> sourceMentions) {
        if (sourceMentions.isEmpty()) {
            return List.of();
        }

        Map<Long, RagEntityMention> sourceByEntityId = new LinkedHashMap<>();
        for (RagEntityMention mention : sourceMentions) {
            sourceByEntityId.putIfAbsent(mention.getEntity().getId(), mention);
        }
        if (sourceByEntityId.isEmpty()) {
            return List.of();
        }

        List<String> targetFiles = graphDocumentStateService.findCompletedEnabledFileMd5sExcluding(
                sourceFileMd5,
                MAX_CROSS_LINK_FILES
        );
        if (targetFiles.isEmpty()) {
            return List.of();
        }

        List<RagEntityMention> targetMentions = mentionRepository.findByEntityIdsAndFileMd5In(
                sourceByEntityId.keySet(),
                targetFiles,
                PageRequest.of(0, MAX_CROSS_TARGET_MENTIONS)
        );
        if (targetMentions.isEmpty()) {
            return List.of();
        }

        List<RagCrossDocumentRelation> crossRelations = new ArrayList<>();
        Set<String> relationKeys = new HashSet<>();
        Map<String, RagEntityMention> firstTargetMentionByEntityAndFile = new LinkedHashMap<>();
        for (RagEntityMention targetMention : targetMentions) {
            if (sourceFileMd5.equals(targetMention.getFileMd5())) {
                continue;
            }
            String key = targetMention.getEntity().getId() + "|" + targetMention.getFileMd5();
            firstTargetMentionByEntityAndFile.putIfAbsent(key, targetMention);
        }

        for (RagEntityMention targetMention : firstTargetMentionByEntityAndFile.values()) {
            RagEntityMention sourceMention = sourceByEntityId.get(targetMention.getEntity().getId());
            if (sourceMention == null) {
                continue;
            }

            RagEntityMention canonicalSource = sourceMention;
            RagEntityMention canonicalTarget = targetMention;
            if (compareMentionFilePosition(targetMention, sourceMention) < 0) {
                canonicalSource = targetMention;
                canonicalTarget = sourceMention;
            }

            String relationKey = canonicalSource.getFileMd5() + "|" + canonicalSource.getChunkId()
                    + "|" + canonicalTarget.getFileMd5() + "|" + canonicalTarget.getChunkId()
                    + "|" + canonicalSource.getEntity().getId() + "|" + canonicalTarget.getEntity().getId()
                    + "|" + RagCrossDocumentRelation.TYPE_SAME_ENTITY;
            if (!relationKeys.add(relationKey)) {
                continue;
            }

            crossRelations.add(createSameEntityCrossDocumentRelation(canonicalSource, canonicalTarget));
            if (crossRelations.size() >= MAX_CROSS_DOCUMENT_RELATIONS_PER_FILE) {
                break;
            }
        }
        return crossRelations;
    }

    private RagCrossDocumentRelation createSameEntityCrossDocumentRelation(RagEntityMention sourceMention,
                                                                          RagEntityMention targetMention) {
        RagCrossDocumentRelation relation = new RagCrossDocumentRelation();
        relation.setSourceEntity(sourceMention.getEntity());
        relation.setTargetEntity(targetMention.getEntity());
        relation.setRelationType(RagCrossDocumentRelation.TYPE_SAME_ENTITY);
        relation.setRelationDescription(limitLength(
                "同一实体在多个已建图文件中出现，用于显式连接跨文档证据: " + sourceMention.getEntity().getName(),
                1000
        ));
        relation.setSourceFileMd5(sourceMention.getFileMd5());
        relation.setSourceChunkId(sourceMention.getChunkId());
        relation.setSourcePageNumber(sourceMention.getPageNumber());
        relation.setSourceAnchorText(sourceMention.getAnchorText());
        relation.setSourceEvidenceText(sourceMention.getEvidenceText());
        relation.setTargetFileMd5(targetMention.getFileMd5());
        relation.setTargetChunkId(targetMention.getChunkId());
        relation.setTargetPageNumber(targetMention.getPageNumber());
        relation.setTargetAnchorText(targetMention.getAnchorText());
        relation.setTargetEvidenceText(targetMention.getEvidenceText());
        relation.setEvidenceText(limitLength(
                "跨文档实体聚合 | 来源证据: " + cleanEvidence(sourceMention.getEvidenceText())
                        + " | 目标证据: " + cleanEvidence(targetMention.getEvidenceText()),
                MAX_EVIDENCE_LENGTH * 2
        ));
        relation.setConfidence(0.75d);
        relation.setBuildMethod(RagCrossDocumentRelation.BUILD_METHOD_RULES);
        relation.setSourceUserId(sourceMention.getUserId());
        relation.setSourceOrgTag(sourceMention.getOrgTag());
        relation.setSourcePublic(sourceMention.isPublic());
        relation.setTargetUserId(targetMention.getUserId());
        relation.setTargetOrgTag(targetMention.getOrgTag());
        relation.setTargetPublic(targetMention.isPublic());
        return relation;
    }

    private RagEntity resolveEntity(CandidateMention candidate, Map<String, RagEntity> entityCache) {
        String key = candidate.type() + "|" + candidate.normalizedName();
        RagEntity cached = entityCache.get(key);
        if (cached != null) {
            return cached;
        }

        RagEntity entity = entityRepository.findByNormalizedNameAndType(candidate.normalizedName(), candidate.type())
                .orElseGet(() -> {
                    RagEntity newEntity = new RagEntity();
                    newEntity.setName(limitLength(candidate.name(), 255));
                    newEntity.setNormalizedName(limitLength(candidate.normalizedName(), 255));
                    newEntity.setType(candidate.type());
                    return entityRepository.save(newEntity);
                });
        entityCache.put(key, entity);
        return entity;
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String relationKeyPart(MentionContext context) {
        return context.candidate().type() + "|" + context.candidate().normalizedName();
    }

    private static int compareMentionFilePosition(RagEntityMention left, RagEntityMention right) {
        int fileCompare = left.getFileMd5().compareTo(right.getFileMd5());
        if (fileCompare != 0) {
            return fileCompare;
        }
        int chunkCompare = left.getChunkId().compareTo(right.getChunkId());
        if (chunkCompare != 0) {
            return chunkCompare;
        }
        Long leftId = left.getId() == null ? Long.MAX_VALUE : left.getId();
        Long rightId = right.getId() == null ? Long.MAX_VALUE : right.getId();
        return leftId.compareTo(rightId);
    }

    private static boolean isContainerType(String type) {
        return "policy".equals(type) || "major".equals(type) || "course".equals(type) || "general".equals(type);
    }

    private static boolean isSubjectType(String type) {
        return "policy".equals(type) || "award".equals(type) || "course".equals(type) || "major".equals(type) || "general".equals(type);
    }

    private static boolean isRequirementTargetType(String type) {
        return "condition".equals(type) || "gpa".equals(type) || "credit".equals(type) || "amount".equals(type) || "time".equals(type);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitSentences(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        String[] parts = content.split("[。！？!?；;\\n]");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = cleanEvidence(part);
            if (StringUtils.hasText(sentence)) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private static String getEvidenceWindow(String content, int start, int end) {
        int from = start;
        while (from > 0 && !isSentenceSeparator(content.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < content.length() && !isSentenceSeparator(content.charAt(to))) {
            to++;
        }
        if (to <= from) {
            from = Math.max(0, start - 80);
            to = Math.min(content.length(), end + 80);
        }
        return limitLength(cleanEvidence(content.substring(from, to)), MAX_EVIDENCE_LENGTH);
    }

    private static boolean isSentenceSeparator(char ch) {
        return ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ';'
                || ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '\r';
    }

    private static String cleanEntityName(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("^[《“\"\\s]+", "")
                .replaceAll("[》”\"\\s]+$", "")
                .trim();
    }

    private static String normalizeEntityName(String value) {
        if (value == null) {
            return "";
        }
        return limitLength(value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[《》“”\"'`（）()\\[\\]{}]", "")
                .replaceAll("\\s+", "")
                .trim(), 255);
    }

    private static boolean isValidEntityName(String normalizedName) {
        return normalizedName.length() >= 2 && normalizedName.length() <= 255;
    }

    private static String cleanEvidence(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String firstText(String first, String fallback) {
        if (StringUtils.hasText(first)) {
            return limitLength(first.trim(), 512);
        }
        return limitLength(fallback == null ? "" : fallback.trim(), 512);
    }

    private static String limitLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    public record GraphExtractionResult(int chunkCount,
                                        int entityCount,
                                        int mentionCount,
                                        int relationCount,
                                        int crossDocumentRelationCount,
                                        int deletedMentionCount,
                                        int deletedRelationCount,
                                        int deletedCrossDocumentRelationCount,
                                        long buildMs) {
    }

    private record EntityPattern(String type, Pattern pattern, int... groups) {
        private MatchText matchText(Matcher matcher) {
            for (int group : groups) {
                if (matcher.groupCount() >= group && matcher.start(group) >= 0) {
                    return new MatchText(matcher.group(group), matcher.start(group), matcher.end(group));
                }
            }
            return null;
        }
    }

    private record MatchText(String text, int start, int end) {
    }

    private record CandidateMention(String name, String normalizedName, String type, String evidenceText) {
    }

    private record MentionContext(CandidateMention candidate, RagEntity entity, RagEntityMention mention) {
    }
}
