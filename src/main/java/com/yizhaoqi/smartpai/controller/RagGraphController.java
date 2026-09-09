package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.RagGraphDocumentState;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.GraphSearchService;
import com.yizhaoqi.smartpai.service.GraphDocumentStateService;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.GraphExtractionService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/rag-graph")
public class RagGraphController {

    private final GraphSearchService graphSearchService;
    private final GraphDocumentStateService graphDocumentStateService;
    private final GraphExtractionService graphExtractionService;
    private final DocumentService documentService;
    private final DocumentVectorRepository documentVectorRepository;
    private final JwtUtils jwtUtils;

    public RagGraphController(GraphSearchService graphSearchService,
                              GraphDocumentStateService graphDocumentStateService,
                              GraphExtractionService graphExtractionService,
                              DocumentService documentService,
                              DocumentVectorRepository documentVectorRepository,
                              JwtUtils jwtUtils) {
        this.graphSearchService = graphSearchService;
        this.graphDocumentStateService = graphDocumentStateService;
        this.graphExtractionService = graphExtractionService;
        this.documentService = documentService;
        this.documentVectorRepository = documentVectorRepository;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping("/document-states/{fileMd5}")
    public ResponseEntity<?> getDocumentState(@PathVariable String fileMd5) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_GET");
        try {
            var state = graphDocumentStateService.findByFileMd5(fileMd5).orElse(null);
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("data", state);
            monitor.end("Graph 文件建图状态查询成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件建图状态查询参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_GET", "admin", "Graph 文件建图状态查询失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件建图状态查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/document-states")
    public ResponseEntity<?> listDocumentStates(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_LIST");
        try {
            Page<?> states = graphDocumentStateService.listStates(page, size);
            Map<String, Object> data = new HashMap<>();
            data.put("items", states.getContent());
            data.put("page", states.getNumber());
            data.put("size", states.getSize());
            data.put("totalElements", states.getTotalElements());
            data.put("totalPages", states.getTotalPages());
            monitor.end("Graph 文件建图状态列表查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_LIST", "admin", "Graph 文件建图状态列表查询失败", e);
            monitor.end("Graph 文件建图状态列表查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/document-states/candidates")
    public ResponseEntity<?> listDocumentGraphCandidates(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_CANDIDATE_LIST");
        try {
            AdminGraphAuthContext authContext = requireAdminContext(authorization);
            List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(authContext.userId(), authContext.orgTags());
            List<FileUpload> candidates = filterGraphManageableFiles(accessibleFiles, keyword);

            int pageNumber = Math.max(page, 1);
            int pageSize = Math.min(Math.max(size, 1), 100);
            int total = candidates.size();
            int fromIndex = Math.min((pageNumber - 1) * pageSize, total);
            int toIndex = Math.min(fromIndex + pageSize, total);

            List<Map<String, Object>> rows = candidates.subList(fromIndex, toIndex).stream()
                    .map(this::toDocumentGraphCandidate)
                    .toList();

            Map<String, Object> data = new HashMap<>();
            data.put("items", rows);
            data.put("data", rows);
            data.put("content", rows);
            data.put("page", pageNumber);
            data.put("number", pageNumber);
            data.put("size", pageSize);
            data.put("totalElements", total);
            data.put("totalPages", (int) Math.ceil(total / (double) pageSize));

            monitor.end("Graph 可管理文件列表查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (GraphRequestException e) {
            monitor.end("Graph 可管理文件列表查询失败: " + e.getMessage());
            return ResponseEntity.status(e.status()).body(Map.of("code", e.status().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_CANDIDATE_LIST", "admin", "Graph 可管理文件列表查询失败", e);
            monitor.end("Graph 可管理文件列表查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @RequestMapping(path = "/document-states/{fileMd5}/enable", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> enableDocumentGraph(@PathVariable String fileMd5,
                                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody(required = false) GraphDocumentStateRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_ENABLE");
        try {
            AdminGraphAuthContext authContext = requireAdminContext(authorization);
            FileUpload file = resolveManageableFile(fileMd5, authContext);
            String graphScope = StringUtils.hasText(request == null ? null : request.graphScope())
                    ? request.graphScope()
                    : defaultGraphScope(file);
            String scopeId = StringUtils.hasText(request == null ? null : request.scopeId())
                    ? request.scopeId()
                    : defaultScopeId(file, graphScope);
            var state = graphDocumentStateService.enableGraph(fileMd5, graphScope, scopeId);
            monitor.end("Graph 文件建图启用成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", state));
        } catch (GraphRequestException e) {
            monitor.end("Graph 文件建图启用失败: " + e.getMessage());
            return ResponseEntity.status(e.status()).body(Map.of("code", e.status().value(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件建图启用参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_ENABLE", "admin", "Graph 文件建图启用失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件建图启用失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @RequestMapping(path = "/document-states/{fileMd5}/disable", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> disableDocumentGraph(@PathVariable String fileMd5,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_DISABLE");
        try {
            AdminGraphAuthContext authContext = requireAdminContext(authorization);
            resolveManageableFile(fileMd5, authContext);
            var state = graphDocumentStateService.disableGraph(fileMd5);
            monitor.end("Graph 文件建图禁用成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", state));
        } catch (GraphRequestException e) {
            monitor.end("Graph 文件建图禁用失败: " + e.getMessage());
            return ResponseEntity.status(e.status()).body(Map.of("code", e.status().value(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件建图禁用参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_DISABLE", "admin", "Graph 文件建图禁用失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件建图禁用失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @RequestMapping(path = "/document-states/{fileMd5}/rebuild", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> rebuildDocumentGraph(@PathVariable String fileMd5,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_REBUILD");
        try {
            AdminGraphAuthContext authContext = requireAdminContext(authorization);
            FileUpload file = resolveManageableFile(fileMd5, authContext);
            RagGraphDocumentState state = graphDocumentStateService.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new GraphRequestException(HttpStatus.BAD_REQUEST, "请先启用建图"));
            if (!state.isGraphEnabled()) {
                throw new GraphRequestException(HttpStatus.BAD_REQUEST, "请先启用建图");
            }

            graphDocumentStateService.markBuilding(fileMd5, file.getUserId(), file.getOrgTag(), file.isPublic());
            long chunkCount = documentVectorRepository.countByFileMd5(fileMd5);
            if (chunkCount <= 0) {
                RagGraphDocumentState failedState = graphDocumentStateService.markFailed(fileMd5, "未找到文档切片，请先完成向量化");
                monitor.end("Graph 文件立即重建失败：未找到文档切片");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "code", HttpStatus.CONFLICT.value(),
                        "message", "未找到文档切片，请先完成向量化",
                        "data", failedState
                ));
            }

            GraphExtractionService.GraphExtractionResult result = graphExtractionService.rebuildForFile(
                    fileMd5,
                    file.getUserId(),
                    file.getOrgTag(),
                    file.isPublic()
            );
            RagGraphDocumentState completedState = graphDocumentStateService.markCompleted(fileMd5, result);
            Map<String, Object> data = new HashMap<>();
            data.put("state", completedState);
            data.put("chunkCount", result.chunkCount());
            data.put("entityCount", result.entityCount());
            data.put("mentionCount", result.mentionCount());
            data.put("relationCount", result.relationCount());
            data.put("crossDocumentRelationCount", result.crossDocumentRelationCount());
            data.put("deletedMentionCount", result.deletedMentionCount());
            data.put("deletedRelationCount", result.deletedRelationCount());
            data.put("deletedCrossDocumentRelationCount", result.deletedCrossDocumentRelationCount());
            data.put("buildMs", result.buildMs());

            monitor.end("Graph 文件立即重建成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (GraphRequestException e) {
            monitor.end("Graph 文件立即重建失败: " + e.getMessage());
            return ResponseEntity.status(e.status()).body(Map.of("code", e.status().value(), "message", e.getMessage()));
        } catch (Exception e) {
            RagGraphDocumentState failedState = markGraphFailedSafely(fileMd5, e);
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_REBUILD", "admin", "Graph 文件立即重建失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件立即重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", 500);
            response.put("message", e.getMessage());
            if (failedState != null) {
                response.put("data", failedState);
            }
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @RequestMapping(path = "/document-states/{fileMd5}/remove", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> removeDocumentGraph(@PathVariable String fileMd5,
                                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_REMOVE");
        try {
            AdminGraphAuthContext authContext = requireAdminContext(authorization);
            resolveManageableFile(fileMd5, authContext);
            GraphDocumentStateService.GraphFileContentCleanupResult cleanupResult =
                    graphDocumentStateService.removeFromGraph(fileMd5);
            RagGraphDocumentState state = graphDocumentStateService.findByFileMd5(fileMd5).orElse(null);

            Map<String, Object> data = new HashMap<>();
            data.put("state", state);
            data.put("deletedMentionCount", cleanupResult.deletedMentionCount());
            data.put("deletedRelationCount", cleanupResult.deletedRelationCount());
            data.put("deletedCrossDocumentRelationCount", cleanupResult.deletedCrossDocumentRelationCount());
            monitor.end("Graph 文件移出图谱成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (GraphRequestException e) {
            monitor.end("Graph 文件移出图谱失败: " + e.getMessage());
            return ResponseEntity.status(e.status()).body(Map.of("code", e.status().value(), "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件移出图谱参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_REMOVE", "admin", "Graph 文件移出图谱失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件移出图谱失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @RequestMapping(path = "/document-states/{fileMd5}/stale", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> markDocumentGraphStale(@PathVariable String fileMd5,
                                                    @RequestBody(required = false) MarkGraphStaleRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_DOCUMENT_STATE_STALE");
        try {
            String reason = request == null ? null : request.reason();
            var state = graphDocumentStateService.markGraphStale(fileMd5, reason);
            monitor.end("Graph 文件建图状态标记为待重建成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", state));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件建图状态标记为待重建参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_DOCUMENT_STATE_STALE", "admin", "Graph 文件建图状态标记为待重建失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件建图状态标记为待重建失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/entities/by-file")
    public ResponseEntity<?> entitiesByFile(@RequestParam String fileMd5,
                                            @RequestParam String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_ENTITIES_BY_FILE");
        try {
            var data = graphSearchService.findEntitySummaryByFileMd5(fileMd5, userId);
            monitor.end("Graph 文件实体查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件实体查询参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_ENTITIES_BY_FILE", userId, "Graph 文件实体查询失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件实体查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/relations/by-file")
    public ResponseEntity<?> relationsByFile(@RequestParam String fileMd5,
                                             @RequestParam String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_RELATIONS_BY_FILE");
        try {
            var data = graphSearchService.findRelationSummaryByFileMd5(fileMd5, userId);
            monitor.end("Graph 文件关系查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 文件关系查询参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_RELATIONS_BY_FILE", userId, "Graph 文件关系查询失败: fileMd5=%s", e, fileMd5);
            monitor.end("Graph 文件关系查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/entities/search")
    public ResponseEntity<?> searchEntities(@RequestParam String keyword,
                                            @RequestParam String userId,
                                            @RequestParam(defaultValue = "50") int limit) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_ENTITY_SEARCH");
        try {
            var data = graphSearchService.searchEntities(keyword, userId, limit);
            monitor.end("Graph 实体关键词查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 实体关键词查询参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_ENTITY_SEARCH", userId, "Graph 实体关键词查询失败: keyword=%s", e, keyword);
            monitor.end("Graph 实体关键词查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/neighbors")
    public ResponseEntity<?> neighbors(@RequestParam Long entityId,
                                       @RequestParam String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_NEIGHBORS");
        try {
            var data = graphSearchService.findNeighbors(entityId, userId);
            monitor.end("Graph 邻居关系查询成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 邻居关系查询参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_NEIGHBORS", userId, "Graph 邻居关系查询失败: entityId=%s", e, entityId);
            monitor.end("Graph 邻居关系查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String query,
                                    @RequestParam String userId,
                                    @RequestParam(defaultValue = "10") int topK) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_SEARCH");
        try {
            var data = graphSearchService.searchWithPermission(query, userId, topK);
            monitor.end("Graph 检索成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 检索参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_SEARCH", userId, "Graph 检索失败: query=%s", e, query);
            monitor.end("Graph 检索失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    @GetMapping("/local-search")
    public ResponseEntity<?> localSearch(@RequestParam String query,
                                         @RequestParam String userId,
                                         @RequestParam(defaultValue = "10") int topK) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RAG_GRAPH_LOCAL_SEARCH");
        try {
            var data = graphSearchService.localSearch(query, userId, topK);
            monitor.end("Graph 局部图检索成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
        } catch (IllegalArgumentException e) {
            monitor.end("Graph 局部图检索参数错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_GRAPH_LOCAL_SEARCH", userId, "Graph 局部图检索失败: query=%s", e, query);
            monitor.end("Graph 局部图检索失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", e.getMessage()));
        }
    }

    private AdminGraphAuthContext requireAdminContext(String authorization) {
        String token = extractBearerToken(authorization);
        if (!StringUtils.hasText(token)) {
            throw new GraphRequestException(HttpStatus.UNAUTHORIZED, "缺少登录凭证");
        }

        String userId = jwtUtils.extractUserIdFromToken(token);
        String role = jwtUtils.extractRoleFromToken(token);
        String orgTags = jwtUtils.extractOrgTagsFromToken(token);
        if (!"ADMIN".equals(role)) {
            throw new GraphRequestException(HttpStatus.FORBIDDEN, "仅管理员可管理知识图谱");
        }
        if (!StringUtils.hasText(userId)) {
            throw new GraphRequestException(HttpStatus.UNAUTHORIZED, "登录凭证缺少用户ID");
        }
        return new AdminGraphAuthContext(userId, orgTags);
    }

    private FileUpload resolveManageableFile(String fileMd5, AdminGraphAuthContext authContext) {
        if (!StringUtils.hasText(fileMd5)) {
            throw new GraphRequestException(HttpStatus.BAD_REQUEST, "fileMd5 不能为空");
        }
        String normalizedFileMd5 = fileMd5.trim();
        return documentService.getAccessibleFiles(authContext.userId(), authContext.orgTags()).stream()
                .filter(file -> normalizedFileMd5.equals(file.getFileMd5()))
                .findFirst()
                .orElseThrow(() -> new GraphRequestException(HttpStatus.NOT_FOUND, "文件不存在或无权限访问"));
    }

    private List<FileUpload> filterGraphManageableFiles(List<FileUpload> files, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        Map<String, FileUpload> deduplicated = new LinkedHashMap<>();
        for (FileUpload file : files == null ? List.<FileUpload>of() : files) {
            if (file == null || file.getStatus() != FileUpload.STATUS_COMPLETED) {
                continue;
            }
            if (StringUtils.hasText(normalizedKeyword)
                    && !safeLower(file.getFileName()).contains(normalizedKeyword)
                    && !safeLower(file.getFileMd5()).contains(normalizedKeyword)) {
                continue;
            }
            deduplicated.putIfAbsent(file.getFileMd5(), file);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private Map<String, Object> toDocumentGraphCandidate(FileUpload file) {
        RagGraphDocumentState state = graphDocumentStateService.findByFileMd5(file.getFileMd5()).orElse(null);
        Map<String, Object> row = new HashMap<>();
        row.put("fileMd5", file.getFileMd5());
        row.put("fileName", file.getFileName());
        row.put("userId", file.getUserId());
        row.put("orgTag", file.getOrgTag());
        row.put("orgTagName", file.getOrgTag());
        row.put("public", file.isPublic());
        row.put("isPublic", file.isPublic());
        row.put("vectorizationStatus", file.getVectorizationStatus());
        row.put("actualChunkCount", file.getActualChunkCount());

        if (state == null) {
            row.put("graphEnabled", false);
            row.put("graphScope", defaultGraphScope(file));
            row.put("scopeId", defaultScopeId(file, defaultGraphScope(file)));
            row.put("graphStatus", RagGraphDocumentState.STATUS_NOT_BUILT);
            row.put("chunkCount", 0);
            row.put("entityCount", 0);
            row.put("mentionCount", 0);
            row.put("relationCount", 0);
            row.put("buildMs", null);
            row.put("lastBuiltAt", null);
            row.put("graphError", null);
            return row;
        }

        row.put("graphEnabled", state.isGraphEnabled());
        row.put("graphScope", state.getGraphScope());
        row.put("scopeId", state.getScopeId());
        row.put("graphStatus", state.getGraphStatus());
        row.put("chunkCount", state.getChunkCount());
        row.put("entityCount", state.getEntityCount());
        row.put("mentionCount", state.getMentionCount());
        row.put("relationCount", state.getRelationCount());
        row.put("buildMs", state.getBuildMs());
        row.put("lastBuiltAt", state.getLastBuiltAt());
        row.put("graphError", state.getGraphError());
        return row;
    }

    private RagGraphDocumentState markGraphFailedSafely(String fileMd5, Exception e) {
        try {
            return graphDocumentStateService.markFailed(fileMd5, e.getMessage());
        } catch (Exception stateException) {
            LogUtils.logBusinessError(
                    "RAG_GRAPH_DOCUMENT_STATE_REBUILD",
                    "admin",
                    "Graph 文件失败状态标记失败: fileMd5=%s",
                    stateException,
                    fileMd5
            );
            return null;
        }
    }

    private String defaultGraphScope(FileUpload file) {
        if (file.isPublic()) {
            return RagGraphDocumentState.SCOPE_ENTERPRISE;
        }
        if (StringUtils.hasText(file.getOrgTag()) && !file.getOrgTag().startsWith("PRIVATE_")) {
            return RagGraphDocumentState.SCOPE_ORG;
        }
        return RagGraphDocumentState.SCOPE_PRIVATE;
    }

    private String defaultScopeId(FileUpload file, String graphScope) {
        String normalizedScope = StringUtils.hasText(graphScope) ? graphScope.trim().toUpperCase() : defaultGraphScope(file);
        if (RagGraphDocumentState.SCOPE_ORG.equals(normalizedScope)) {
            return file.getOrgTag();
        }
        if (RagGraphDocumentState.SCOPE_PRIVATE.equals(normalizedScope)) {
            return file.getUserId();
        }
        return "enterprise";
    }

    private String extractBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String trimmed = authorization.trim();
        return trimmed.startsWith("Bearer ") ? trimmed.substring(7) : trimmed;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}

record GraphDocumentStateRequest(String graphScope, String scopeId) {}
record MarkGraphStaleRequest(String reason) {}
record AdminGraphAuthContext(String userId, String orgTags) {}

class GraphRequestException extends RuntimeException {
    private final HttpStatus status;

    GraphRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
