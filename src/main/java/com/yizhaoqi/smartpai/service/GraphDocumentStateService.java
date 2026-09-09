package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.RagGraphDocumentState;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.RagCrossDocumentRelationRepository;
import com.yizhaoqi.smartpai.repository.RagEntityMentionRepository;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.RagGraphDocumentStateRepository;
import com.yizhaoqi.smartpai.repository.RagRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class GraphDocumentStateService {

    private static final Logger logger = LoggerFactory.getLogger(GraphDocumentStateService.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final RagGraphDocumentStateRepository stateRepository;
    private final FileUploadRepository fileUploadRepository;
    private final RagEntityRepository entityRepository;
    private final RagEntityMentionRepository mentionRepository;
    private final RagRelationRepository relationRepository;
    private final RagCrossDocumentRelationRepository crossDocumentRelationRepository;

    public GraphDocumentStateService(RagGraphDocumentStateRepository stateRepository,
                                     FileUploadRepository fileUploadRepository,
                                     RagEntityRepository entityRepository,
                                     RagEntityMentionRepository mentionRepository,
                                     RagRelationRepository relationRepository,
                                     RagCrossDocumentRelationRepository crossDocumentRelationRepository) {
        this.stateRepository = stateRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.relationRepository = relationRepository;
        this.crossDocumentRelationRepository = crossDocumentRelationRepository;
    }

    @Transactional(readOnly = true)
    public Optional<RagGraphDocumentState> findByFileMd5(String fileMd5) {
        validateFileMd5(fileMd5);
        return stateRepository.findByFileMd5(fileMd5);
    }

    @Transactional(readOnly = true)
    public Page<RagGraphDocumentState> listStates(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return stateRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(safePage, safeSize));
    }

    @Transactional(readOnly = true)
    public List<String> findCompletedEnabledFileMd5sExcluding(String fileMd5, int limit) {
        validateFileMd5(fileMd5);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return stateRepository.findEnabledFileMd5sByGraphStatusExcluding(
                RagGraphDocumentState.STATUS_COMPLETED,
                fileMd5.trim(),
                PageRequest.of(0, safeLimit)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GraphFileCleanupResult deleteGraphByFile(String fileMd5) {
        GraphFileContentCleanupResult contentCleanup = deleteGraphContent(fileMd5);
        int deletedStates = stateRepository.deleteByFileMd5(fileMd5.trim());
        logger.info(
                "已清理文件图谱数据: fileMd5={}, deletedMentions={}, deletedRelations={}, deletedCrossDocumentRelations={}, deletedStates={}",
                fileMd5,
                contentCleanup.deletedMentionCount(),
                contentCleanup.deletedRelationCount(),
                contentCleanup.deletedCrossDocumentRelationCount(),
                deletedStates
        );
        return new GraphFileCleanupResult(
                contentCleanup.deletedMentionCount(),
                contentCleanup.deletedRelationCount(),
                contentCleanup.deletedCrossDocumentRelationCount(),
                deletedStates
        );
    }

    @Transactional
    public GraphFileContentCleanupResult deleteGraphContentByFile(String fileMd5) {
        return deleteGraphContent(fileMd5);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagGraphDocumentState markGraphStale(String fileMd5, String reason) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5.trim())
                .orElseGet(() -> createDefaultState(fileMd5));
        syncLatestFileMetadata(state);
        state.setGraphStatus(RagGraphDocumentState.STATUS_NOT_BUILT);
        state.setGraphError(limitError(reason));
        return stateRepository.save(state);
    }

    @Transactional
    public GraphPermissionSyncResult syncGraphPermission(String fileMd5,
                                                        String userId,
                                                        String orgTag,
                                                        boolean isPublic) {
        validateFileMd5(fileMd5);
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        String normalizedFileMd5 = fileMd5.trim();
        String normalizedUserId = userId.trim();
        String normalizedOrgTag = StringUtils.hasText(orgTag) ? orgTag.trim() : null;

        RagGraphDocumentState state = stateRepository.findByFileMd5(normalizedFileMd5)
                .orElseGet(() -> createDefaultState(normalizedFileMd5));
        state.setUserId(normalizedUserId);
        state.setOrgTag(normalizedOrgTag);
        state.setPublic(isPublic);
        syncScopeForPermission(state, normalizedUserId, normalizedOrgTag);
        RagGraphDocumentState savedState = stateRepository.save(state);

        int updatedMentions = mentionRepository.updatePermissionByFileMd5(
                normalizedFileMd5,
                normalizedUserId,
                normalizedOrgTag,
                isPublic
        );
        int updatedRelations = relationRepository.updatePermissionByFileMd5(
                normalizedFileMd5,
                normalizedUserId,
                normalizedOrgTag,
                isPublic
        );
        int updatedSourceCrossRelations = crossDocumentRelationRepository.updateSourcePermissionByFileMd5(
                normalizedFileMd5,
                normalizedUserId,
                normalizedOrgTag,
                isPublic
        );
        int updatedTargetCrossRelations = crossDocumentRelationRepository.updateTargetPermissionByFileMd5(
                normalizedFileMd5,
                normalizedUserId,
                normalizedOrgTag,
                isPublic
        );
        logger.info(
                "已同步文件图谱权限: fileMd5={}, userId={}, orgTag={}, isPublic={}, updatedMentions={}, updatedRelations={}, updatedSourceCrossRelations={}, updatedTargetCrossRelations={}",
                normalizedFileMd5,
                normalizedUserId,
                normalizedOrgTag,
                isPublic,
                updatedMentions,
                updatedRelations,
                updatedSourceCrossRelations,
                updatedTargetCrossRelations
        );
        return new GraphPermissionSyncResult(
                savedState,
                updatedMentions,
                updatedRelations,
                updatedSourceCrossRelations,
                updatedTargetCrossRelations
        );
    }

    @Transactional
    public int cleanupOrphanEntities() {
        int deletedEntities = entityRepository.deleteOrphanEntities();
        logger.info("已清理孤儿图谱实体: deletedEntities={}", deletedEntities);
        return deletedEntities;
    }

    @Transactional
    public RagGraphDocumentState getOrCreateState(String fileMd5,
                                                  String userId,
                                                  String orgTag,
                                                  boolean isPublic) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5)
                .orElseGet(() -> createDefaultState(fileMd5));
        syncRuntimeMetadata(state, userId, orgTag, isPublic);
        return stateRepository.save(state);
    }

    @Transactional
    public RagGraphDocumentState enableGraph(String fileMd5, String graphScope, String scopeId) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5)
                .orElseGet(() -> createDefaultState(fileMd5));
        syncLatestFileMetadata(state);

        String normalizedScope = normalizeScope(graphScope);
        String normalizedScopeId = normalizeScopeId(normalizedScope, scopeId);
        validateScopeMatchesMetadata(state, normalizedScope, normalizedScopeId);
        state.setGraphScope(normalizedScope);
        state.setScopeId(normalizedScopeId);
        state.setGraphEnabled(true);
        if (!StringUtils.hasText(state.getGraphStatus())) {
            state.setGraphStatus(RagGraphDocumentState.STATUS_NOT_BUILT);
        }
        return stateRepository.save(state);
    }

    @Transactional
    public RagGraphDocumentState disableGraph(String fileMd5) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5)
                .orElseGet(() -> createDefaultState(fileMd5));
        syncLatestFileMetadata(state);
        state.setGraphEnabled(false);
        if (!StringUtils.hasText(state.getGraphStatus())) {
            state.setGraphStatus(RagGraphDocumentState.STATUS_NOT_BUILT);
        }
        return stateRepository.save(state);
    }

    @Transactional
    public GraphFileContentCleanupResult removeFromGraph(String fileMd5) {
        validateFileMd5(fileMd5);
        String normalizedFileMd5 = fileMd5.trim();
        RagGraphDocumentState state = stateRepository.findByFileMd5(normalizedFileMd5)
                .orElseGet(() -> createDefaultState(normalizedFileMd5));
        syncLatestFileMetadata(state);

        GraphFileContentCleanupResult cleanupResult = deleteGraphContent(normalizedFileMd5);
        state.setGraphEnabled(false);
        state.setGraphStatus(RagGraphDocumentState.STATUS_NOT_BUILT);
        state.setChunkCount(0);
        state.setEntityCount(0);
        state.setMentionCount(0);
        state.setRelationCount(0);
        state.setBuildMs(null);
        state.setLastBuiltAt(null);
        state.setGraphError("已由管理员移出图谱，原知识库文件和向量数据未删除");
        stateRepository.save(state);

        logger.info(
                "已将文件移出图谱: fileMd5={}, deletedMentions={}, deletedRelations={}, deletedCrossDocumentRelations={}",
                normalizedFileMd5,
                cleanupResult.deletedMentionCount(),
                cleanupResult.deletedRelationCount(),
                cleanupResult.deletedCrossDocumentRelationCount()
        );
        return cleanupResult;
    }

    @Transactional(readOnly = true)
    public boolean isAutoBuildAllowed(String fileMd5, String userId, String orgTag, boolean isPublic) {
        if (!StringUtils.hasText(fileMd5)) {
            return false;
        }

        Optional<RagGraphDocumentState> optionalState = stateRepository.findByFileMd5(fileMd5);
        if (optionalState.isEmpty()) {
            return false;
        }

        RagGraphDocumentState state = optionalState.get();
        if (!state.isGraphEnabled()) {
            return false;
        }

        String graphScope = normalizeScope(state.getGraphScope());
        if (RagGraphDocumentState.SCOPE_ORG.equals(graphScope)) {
            return StringUtils.hasText(orgTag) && orgTag.equals(state.getScopeId());
        }
        if (RagGraphDocumentState.SCOPE_PRIVATE.equals(graphScope)) {
            return StringUtils.hasText(userId) && userId.equals(state.getScopeId());
        }
        return true;
    }

    @Transactional
    public RagGraphDocumentState markBuilding(String fileMd5,
                                              String userId,
                                              String orgTag,
                                              boolean isPublic) {
        RagGraphDocumentState state = getOrCreateState(fileMd5, userId, orgTag, isPublic);
        state.setGraphStatus(RagGraphDocumentState.STATUS_BUILDING);
        state.setBuildMs(null);
        state.setGraphError(null);
        return stateRepository.save(state);
    }

    @Transactional
    public RagGraphDocumentState markCompleted(String fileMd5, GraphExtractionService.GraphExtractionResult result) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5)
                .orElseGet(() -> createDefaultState(fileMd5));
        state.setGraphStatus(RagGraphDocumentState.STATUS_COMPLETED);
        state.setChunkCount(result.chunkCount());
        state.setEntityCount(result.entityCount());
        state.setMentionCount(result.mentionCount());
        state.setRelationCount(result.relationCount());
        state.setBuildMs(result.buildMs());
        state.setLastBuiltAt(LocalDateTime.now());
        state.setGraphError(null);
        return stateRepository.save(state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagGraphDocumentState markFailed(String fileMd5, String errorMessage) {
        validateFileMd5(fileMd5);
        RagGraphDocumentState state = stateRepository.findByFileMd5(fileMd5)
                .orElseGet(() -> createDefaultState(fileMd5));
        state.setGraphStatus(RagGraphDocumentState.STATUS_FAILED);
        state.setGraphError(limitError(errorMessage));
        return stateRepository.save(state);
    }

    private RagGraphDocumentState createDefaultState(String fileMd5) {
        RagGraphDocumentState state = new RagGraphDocumentState();
        state.setFileMd5(fileMd5.trim());
        syncLatestFileMetadata(state);
        return state;
    }

    private void syncLatestFileMetadata(RagGraphDocumentState state) {
        fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(state.getFileMd5())
                .ifPresent(file -> syncFileMetadata(state, file));
    }

    private void syncRuntimeMetadata(RagGraphDocumentState state,
                                     String userId,
                                     String orgTag,
                                     boolean isPublic) {
        syncLatestFileMetadata(state);
        if (StringUtils.hasText(userId)) {
            state.setUserId(userId.trim());
        }
        if (StringUtils.hasText(orgTag)) {
            state.setOrgTag(orgTag.trim());
        }
        state.setPublic(isPublic);
    }

    private GraphFileContentCleanupResult deleteGraphContent(String fileMd5) {
        validateFileMd5(fileMd5);
        String normalizedFileMd5 = fileMd5.trim();
        int deletedCrossDocumentRelations = crossDocumentRelationRepository.deleteByAnyFileMd5(normalizedFileMd5);
        int deletedRelations = relationRepository.deleteByFileMd5(normalizedFileMd5);
        int deletedMentions = mentionRepository.deleteByFileMd5(normalizedFileMd5);
        return new GraphFileContentCleanupResult(deletedMentions, deletedRelations, deletedCrossDocumentRelations);
    }

    private void syncScopeForPermission(RagGraphDocumentState state, String userId, String orgTag) {
        String graphScope = normalizeScope(state.getGraphScope());
        state.setGraphScope(graphScope);

        if (RagGraphDocumentState.SCOPE_ORG.equals(graphScope)) {
            if (!StringUtils.hasText(orgTag)) {
                throw new IllegalArgumentException("ORG 图谱权限同步需要 orgTag");
            }
            state.setScopeId(orgTag);
            return;
        }

        if (RagGraphDocumentState.SCOPE_PRIVATE.equals(graphScope)) {
            state.setScopeId(userId);
            return;
        }

        if (!StringUtils.hasText(state.getScopeId())) {
            state.setScopeId("enterprise");
        }
    }

    private void syncFileMetadata(RagGraphDocumentState state, FileUpload file) {
        if (StringUtils.hasText(file.getFileName())) {
            state.setFileName(file.getFileName());
        }
        if (StringUtils.hasText(file.getUserId())) {
            state.setUserId(file.getUserId());
        }
        if (StringUtils.hasText(file.getOrgTag())) {
            state.setOrgTag(file.getOrgTag());
        }
        state.setPublic(file.isPublic());
    }

    private static String normalizeScope(String graphScope) {
        String normalized = StringUtils.hasText(graphScope)
                ? graphScope.trim().toUpperCase(Locale.ROOT)
                : RagGraphDocumentState.SCOPE_ENTERPRISE;
        if (!RagGraphDocumentState.SCOPE_ENTERPRISE.equals(normalized)
                && !RagGraphDocumentState.SCOPE_ORG.equals(normalized)
                && !RagGraphDocumentState.SCOPE_PRIVATE.equals(normalized)) {
            throw new IllegalArgumentException("graphScope 仅支持 ENTERPRISE / ORG / PRIVATE");
        }
        return normalized;
    }

    private static String normalizeScopeId(String graphScope, String scopeId) {
        if (RagGraphDocumentState.SCOPE_ENTERPRISE.equals(graphScope)) {
            return StringUtils.hasText(scopeId) ? scopeId.trim() : "enterprise";
        }
        if (!StringUtils.hasText(scopeId)) {
            throw new IllegalArgumentException(graphScope + " scopeId 不能为空");
        }
        return scopeId.trim();
    }

    private static void validateScopeMatchesMetadata(RagGraphDocumentState state, String graphScope, String scopeId) {
        if (RagGraphDocumentState.SCOPE_ORG.equals(graphScope)
                && StringUtils.hasText(state.getOrgTag())
                && !state.getOrgTag().equals(scopeId)) {
            throw new IllegalArgumentException("ORG scopeId 必须等于文件 orgTag");
        }
        if (RagGraphDocumentState.SCOPE_PRIVATE.equals(graphScope)
                && StringUtils.hasText(state.getUserId())
                && !state.getUserId().equals(scopeId)) {
            throw new IllegalArgumentException("PRIVATE scopeId 必须等于文件 userId");
        }
    }

    private static void validateFileMd5(String fileMd5) {
        if (!StringUtils.hasText(fileMd5)) {
            throw new IllegalArgumentException("fileMd5 不能为空");
        }
    }

    private static String limitError(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return null;
        }
        String trimmed = errorMessage.trim();
        if (trimmed.length() <= MAX_ERROR_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_LENGTH);
    }

    public record GraphFileContentCleanupResult(int deletedMentionCount,
                                                int deletedRelationCount,
                                                int deletedCrossDocumentRelationCount) {
    }

    public record GraphFileCleanupResult(int deletedMentionCount,
                                         int deletedRelationCount,
                                         int deletedCrossDocumentRelationCount,
                                         int deletedStateCount) {
    }

    public record GraphPermissionSyncResult(RagGraphDocumentState state,
                                            int updatedMentionCount,
                                            int updatedRelationCount,
                                            int updatedSourceCrossRelationCount,
                                            int updatedTargetCrossRelationCount) {
    }
}
