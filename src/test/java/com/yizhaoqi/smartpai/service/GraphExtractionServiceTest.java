package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.RagEntityMention;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExtractionServiceTest {

    private DocumentVectorRepository documentVectorRepository;
    private RagEntityRepository entityRepository;
    private RagEntityMentionRepository mentionRepository;
    private RagRelationRepository relationRepository;
    private RagCrossDocumentRelationRepository crossDocumentRelationRepository;
    private GraphDocumentStateService graphDocumentStateService;
    private GraphExtractionService service;

    @BeforeEach
    void setUp() {
        documentVectorRepository = mock(DocumentVectorRepository.class);
        entityRepository = mock(RagEntityRepository.class);
        mentionRepository = mock(RagEntityMentionRepository.class);
        relationRepository = mock(RagRelationRepository.class);
        crossDocumentRelationRepository = mock(RagCrossDocumentRelationRepository.class);
        graphDocumentStateService = mock(GraphDocumentStateService.class);
        service = new GraphExtractionService(
                documentVectorRepository,
                entityRepository,
                mentionRepository,
                relationRepository,
                crossDocumentRelationRepository,
                graphDocumentStateService
        );
    }

    @Test
    void rebuildForFileCreatesSameEntityCrossDocumentRelationWithBothEvidenceSides() {
        when(graphDocumentStateService.deleteGraphContentByFile("file-a"))
                .thenReturn(new GraphDocumentStateService.GraphFileContentCleanupResult(0, 0, 0));
        when(graphDocumentStateService.findCompletedEnabledFileMd5sExcluding("file-a", 80))
                .thenReturn(List.of("file-b"));
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc("file-a"))
                .thenReturn(List.of(chunk("file-a", 1, "研究生国家奖学金用于奖励优秀研究生。")));
        when(entityRepository.findByNormalizedNameAndType(any(), any())).thenReturn(Optional.empty());

        AtomicLong idSequence = new AtomicLong(1L);
        AtomicReference<RagEntity> awardEntity = new AtomicReference<>();
        when(entityRepository.save(any(RagEntity.class))).thenAnswer(invocation -> {
            RagEntity entity = invocation.getArgument(0);
            entity.setId(idSequence.getAndIncrement());
            if ("award".equals(entity.getType())) {
                awardEntity.set(entity);
            }
            return entity;
        });
        when(mentionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(crossDocumentRelationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mentionRepository.findByEntityIdsAndFileMd5In(any(Collection.class), eq(List.of("file-b")), any(Pageable.class)))
                .thenAnswer(invocation -> List.of(targetMention(awardEntity.get())));

        GraphExtractionService.GraphExtractionResult result = service.rebuildForFile("file-a", "2", "TEAM_A", true);

        assertEquals(1, result.crossDocumentRelationCount());
        ArgumentCaptor<Iterable<RagCrossDocumentRelation>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(crossDocumentRelationRepository).saveAll(captor.capture());
        RagCrossDocumentRelation relation = captor.getValue().iterator().next();
        assertEquals(RagCrossDocumentRelation.TYPE_SAME_ENTITY, relation.getRelationType());
        assertEquals("file-a", relation.getSourceFileMd5());
        assertEquals("file-b", relation.getTargetFileMd5());
        assertTrue(relation.getSourceEvidenceText().contains("研究生国家奖学金"));
        assertTrue(relation.getTargetEvidenceText().contains("培养方案"));
        assertTrue(relation.isSourcePublic());
        assertEquals("TEAM_A", relation.getTargetOrgTag());
    }

    private DocumentVector chunk(String fileMd5, int chunkId, String content) {
        DocumentVector chunk = new DocumentVector();
        chunk.setFileMd5(fileMd5);
        chunk.setChunkId(chunkId);
        chunk.setPageNumber(1);
        chunk.setAnchorText("anchor");
        chunk.setTextContent(content);
        return chunk;
    }

    private RagEntityMention targetMention(RagEntity entity) {
        RagEntityMention mention = new RagEntityMention();
        mention.setId(2L);
        mention.setEntity(entity);
        mention.setFileMd5("file-b");
        mention.setChunkId(2);
        mention.setPageNumber(3);
        mention.setAnchorText("培养方案");
        mention.setEvidenceText("培养方案也提到研究生国家奖学金。");
        mention.setUserId("3");
        mention.setOrgTag("TEAM_A");
        mention.setPublic(false);
        return mention;
    }
}
