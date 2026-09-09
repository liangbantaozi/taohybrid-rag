package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.RagGraphDocumentState;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagGraphDocumentStateRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphDocumentStateServiceTest {

    @Mock
    private RagGraphDocumentStateRepository stateRepository;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private RagEntityRepository entityRepository;

    @Mock
    private RagEntityMentionRepository mentionRepository;

    @Mock
    private RagRelationRepository relationRepository;

    @Mock
    private RagCrossDocumentRelationRepository crossDocumentRelationRepository;

    private GraphDocumentStateService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GraphDocumentStateService(
                stateRepository,
                fileUploadRepository,
                entityRepository,
                mentionRepository,
                relationRepository,
                crossDocumentRelationRepository
        );
        when(stateRepository.save(any(RagGraphDocumentState.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void deleteGraphByFileRemovesRelationsMentionsAndStateButKeepsEntities() {
        when(relationRepository.deleteByFileMd5("md5")).thenReturn(3);
        when(crossDocumentRelationRepository.deleteByAnyFileMd5("md5")).thenReturn(4);
        when(mentionRepository.deleteByFileMd5("md5")).thenReturn(2);
        when(stateRepository.deleteByFileMd5("md5")).thenReturn(1);

        GraphDocumentStateService.GraphFileCleanupResult result = service.deleteGraphByFile("md5");

        assertEquals(2, result.deletedMentionCount());
        assertEquals(3, result.deletedRelationCount());
        assertEquals(4, result.deletedCrossDocumentRelationCount());
        assertEquals(1, result.deletedStateCount());

        InOrder inOrder = inOrder(crossDocumentRelationRepository, relationRepository, mentionRepository, stateRepository);
        inOrder.verify(crossDocumentRelationRepository).deleteByAnyFileMd5("md5");
        inOrder.verify(relationRepository).deleteByFileMd5("md5");
        inOrder.verify(mentionRepository).deleteByFileMd5("md5");
        inOrder.verify(stateRepository).deleteByFileMd5("md5");
    }

    @Test
    void syncGraphPermissionUpdatesStateMentionsAndRelations() {
        RagGraphDocumentState state = new RagGraphDocumentState();
        state.setFileMd5("md5");
        state.setGraphScope(RagGraphDocumentState.SCOPE_ORG);
        state.setScopeId("OLD_TEAM");

        when(stateRepository.findByFileMd5("md5")).thenReturn(Optional.of(state));
        when(mentionRepository.updatePermissionByFileMd5("md5", "7", "TEAM_A", true)).thenReturn(4);
        when(relationRepository.updatePermissionByFileMd5("md5", "7", "TEAM_A", true)).thenReturn(5);
        when(crossDocumentRelationRepository.updateSourcePermissionByFileMd5("md5", "7", "TEAM_A", true)).thenReturn(6);
        when(crossDocumentRelationRepository.updateTargetPermissionByFileMd5("md5", "7", "TEAM_A", true)).thenReturn(7);

        GraphDocumentStateService.GraphPermissionSyncResult result =
                service.syncGraphPermission("md5", "7", "TEAM_A", true);

        assertEquals(4, result.updatedMentionCount());
        assertEquals(5, result.updatedRelationCount());
        assertEquals(6, result.updatedSourceCrossRelationCount());
        assertEquals(7, result.updatedTargetCrossRelationCount());
        assertEquals("7", result.state().getUserId());
        assertEquals("TEAM_A", result.state().getOrgTag());
        assertEquals("TEAM_A", result.state().getScopeId());
        assertEquals(true, result.state().isPublic());

        verify(mentionRepository).updatePermissionByFileMd5("md5", "7", "TEAM_A", true);
        verify(relationRepository).updatePermissionByFileMd5("md5", "7", "TEAM_A", true);
        verify(crossDocumentRelationRepository).updateSourcePermissionByFileMd5("md5", "7", "TEAM_A", true);
        verify(crossDocumentRelationRepository).updateTargetPermissionByFileMd5("md5", "7", "TEAM_A", true);
    }

    @Test
    void markGraphStaleSetsNotBuiltAndKeepsReason() {
        RagGraphDocumentState state = new RagGraphDocumentState();
        state.setFileMd5("md5");
        state.setGraphStatus(RagGraphDocumentState.STATUS_COMPLETED);

        when(stateRepository.findByFileMd5("md5")).thenReturn(Optional.of(state));

        service.markGraphStale("md5", "permission changed");

        ArgumentCaptor<RagGraphDocumentState> captor = ArgumentCaptor.forClass(RagGraphDocumentState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals(RagGraphDocumentState.STATUS_NOT_BUILT, captor.getValue().getGraphStatus());
        assertEquals("permission changed", captor.getValue().getGraphError());
    }

    @Test
    void markCompletedStoresBuildCostMetrics() {
        RagGraphDocumentState state = new RagGraphDocumentState();
        state.setFileMd5("md5");

        when(stateRepository.findByFileMd5("md5")).thenReturn(Optional.of(state));

        service.markCompleted("md5", new GraphExtractionService.GraphExtractionResult(4, 54, 62, 414, 12, 0, 0, 0, 128L));

        ArgumentCaptor<RagGraphDocumentState> captor = ArgumentCaptor.forClass(RagGraphDocumentState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals(RagGraphDocumentState.STATUS_COMPLETED, captor.getValue().getGraphStatus());
        assertEquals(4, captor.getValue().getChunkCount());
        assertEquals(54, captor.getValue().getEntityCount());
        assertEquals(62, captor.getValue().getMentionCount());
        assertEquals(414, captor.getValue().getRelationCount());
        assertEquals(128L, captor.getValue().getBuildMs());
    }
}
