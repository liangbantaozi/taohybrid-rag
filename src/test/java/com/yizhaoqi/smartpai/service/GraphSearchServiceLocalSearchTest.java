package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.RagEntityMention;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphSearchServiceLocalSearchTest {

    private RagEntityRepository entityRepository;
    private RagEntityMentionRepository mentionRepository;
    private RagRelationRepository relationRepository;
    private RagCrossDocumentRelationRepository crossDocumentRelationRepository;
    private UserRepository userRepository;
    private OrgTagCacheService orgTagCacheService;
    private FileUploadRepository fileUploadRepository;
    private GraphSearchService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(RagEntityRepository.class);
        mentionRepository = mock(RagEntityMentionRepository.class);
        relationRepository = mock(RagRelationRepository.class);
        crossDocumentRelationRepository = mock(RagCrossDocumentRelationRepository.class);
        userRepository = mock(UserRepository.class);
        orgTagCacheService = mock(OrgTagCacheService.class);
        fileUploadRepository = mock(FileUploadRepository.class);
        service = new GraphSearchService(
                entityRepository,
                mentionRepository,
                relationRepository,
                crossDocumentRelationRepository,
                userRepository,
                orgTagCacheService,
                fileUploadRepository
        );
    }

    @Test
    void localSearchReturnsCrossDocumentEdgesAndBothEvidenceChunks() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));

        RagEntity entity = entity(10L, "研究生国家奖学金", "研究生国家奖学金", "award");
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(entity));

        RagEntityMention mention = mention(entity, "file-a", 1, "source evidence");
        when(mentionRepository.findByEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(mention));
        when(mentionRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(relationRepository.findByAnyEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(relationRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());

        RagCrossDocumentRelation crossRelation = crossRelation(entity, "file-a", "file-b");
        when(crossDocumentRelationRepository.findByAnyEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(crossRelation));
        when(crossDocumentRelationRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(fileUploadRepository.findByFileMd5In(any()))
                .thenReturn(List.of(file("file-a", "A.pdf"), file("file-b", "B.pdf")));

        GraphSearchService.GraphLocalSearchResult result = service.localSearch("奖学金和培养方案有什么关系", "2", 5);

        assertEquals(1, result.edges().size());
        assertEquals("CROSS_DOCUMENT", result.edges().get(0).relationScope());
        assertEquals(2, result.sourceChunks().size());
        assertTrue(result.sourceChunks().stream().anyMatch(chunk -> "file-a".equals(chunk.getFileMd5())));
        assertTrue(result.sourceChunks().stream().anyMatch(chunk -> "file-b".equals(chunk.getFileMd5())));
        assertEquals(1, result.nodes().size());
        assertEquals(2, result.nodes().get(0).sourceFileCount());
    }

    @Test
    void localSearchWithoutCrossDocumentRelationsStillReturnsLegacyGraphEvidence() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));

        RagEntity entity = entity(10L, "研究生国家奖学金", "研究生国家奖学金", "award");
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(mentionRepository.findByEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(mention(entity, "file-a", 1, "legacy mention evidence")));
        when(mentionRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(relationRepository.findByAnyEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(relationRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(crossDocumentRelationRepository.findByAnyEntityIdsWithPermission(any(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(crossDocumentRelationRepository.searchEvidenceWithPermission(anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());
        when(fileUploadRepository.findByFileMd5In(any()))
                .thenReturn(List.of(file("file-a", "A.pdf")));

        GraphSearchService.GraphLocalSearchResult result = service.localSearch("研究生国家奖学金", "2", 5);

        assertEquals(0, result.edges().size());
        assertEquals(1, result.sourceChunks().size());
        assertEquals("file-a", result.sourceChunks().get(0).getFileMd5());
    }

    @Test
    void searchWithPermissionReturnsEmptyWhenLocalAndLegacyGraphBothFail() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenThrow(new RuntimeException("graph table unavailable"));

        assertTrue(service.searchWithPermission("奖学金和培养方案有什么关系", "2", 5).isEmpty());
    }

    private RagEntity entity(Long id, String name, String normalizedName, String type) {
        RagEntity entity = new RagEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setType(type);
        return entity;
    }

    private RagEntityMention mention(RagEntity entity, String fileMd5, int chunkId, String evidence) {
        RagEntityMention mention = new RagEntityMention();
        mention.setEntity(entity);
        mention.setFileMd5(fileMd5);
        mention.setChunkId(chunkId);
        mention.setPageNumber(chunkId);
        mention.setAnchorText(evidence);
        mention.setEvidenceText(evidence);
        mention.setUserId("2");
        mention.setOrgTag("TEAM_A");
        mention.setPublic(false);
        return mention;
    }

    private RagCrossDocumentRelation crossRelation(RagEntity entity, String sourceFileMd5, String targetFileMd5) {
        RagCrossDocumentRelation relation = new RagCrossDocumentRelation();
        relation.setId(1L);
        relation.setSourceEntity(entity);
        relation.setTargetEntity(entity);
        relation.setRelationType(RagCrossDocumentRelation.TYPE_SAME_ENTITY);
        relation.setRelationDescription("同一实体跨文档出现");
        relation.setConfidence(0.75d);
        relation.setBuildMethod(RagCrossDocumentRelation.BUILD_METHOD_RULES);
        relation.setSourceFileMd5(sourceFileMd5);
        relation.setSourceChunkId(1);
        relation.setSourcePageNumber(1);
        relation.setSourceAnchorText("source");
        relation.setSourceEvidenceText("source evidence");
        relation.setSourceUserId("2");
        relation.setSourceOrgTag("TEAM_A");
        relation.setSourcePublic(false);
        relation.setTargetFileMd5(targetFileMd5);
        relation.setTargetChunkId(2);
        relation.setTargetPageNumber(2);
        relation.setTargetAnchorText("target");
        relation.setTargetEvidenceText("target evidence");
        relation.setTargetUserId("2");
        relation.setTargetOrgTag("TEAM_A");
        relation.setTargetPublic(false);
        return relation;
    }

    private FileUpload file(String fileMd5, String fileName) {
        FileUpload file = new FileUpload();
        file.setFileMd5(fileMd5);
        file.setFileName(fileName);
        return file;
    }
}
