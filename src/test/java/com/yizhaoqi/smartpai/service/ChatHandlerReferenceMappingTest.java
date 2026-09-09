package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatHandlerReferenceMappingTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ChatGenerationStateService chatGenerationStateService;

    private ChatHandler chatHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        chatHandler = new ChatHandler(
                redisTemplate,
                mock(HybridSearchService.class),
                mock(LlmProviderRouter.class),
                mock(RateLimitService.class),
                mock(ConversationService.class),
                chatGenerationStateService,
                mock(ChatSessionRegistry.class),
                mock(AgentToolRegistry.class),
                mock(GraphQueryIntentService.class),
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class)
        );
    }

    @Test
    void consecutiveSearchToolsAppendReferenceNumbers() {
        ChatHandler.ReferenceMergeResult searchMerge = chatHandler.mergeReferencesFromSearchTool(
                "gen-1",
                "query",
                "search_knowledge",
                toolResult("search_knowledge", List.of(
                        result("md5-a", 1, "a.txt", "HYBRID"),
                        result("md5-b", 2, "b.txt", "HYBRID")
                ))
        );
        ChatHandler.ReferenceMergeResult graphMerge = chatHandler.mergeReferencesFromSearchTool(
                "gen-1",
                "query",
                "graph_search_knowledge",
                toolResult("graph_search_knowledge", List.of(
                        result("md5-c", 3, "c.txt", "GRAPH"),
                        result("md5-d", 4, "d.txt", "GRAPH")
                ))
        );

        assertEquals(Map.of(1, 1, 2, 2), searchMerge.referenceNumberByToolIndex());
        assertEquals(Map.of(1, 3, 2, 4), graphMerge.referenceNumberByToolIndex());
        assertEquals("md5-a", chatHandler.getReferenceDetail("gen-1", 1).fileMd5());
        assertEquals("md5-b", chatHandler.getReferenceDetail("gen-1", 2).fileMd5());
        assertEquals("md5-c", chatHandler.getReferenceDetail("gen-1", 3).fileMd5());
        assertEquals("md5-d", chatHandler.getReferenceDetail("gen-1", 4).fileMd5());
        assertEquals("graph_search_knowledge", chatHandler.getReferenceDetail("gen-1", 4).sourceTool());
        assertEquals("Graph关系证据补充：\n\n[3] c.txt\n[4] d.txt",
                chatHandler.rewriteToolReferenceNumbers("Graph关系证据补充：\n\n[1] c.txt\n[2] d.txt",
                        graphMerge.referenceNumberByToolIndex()));
        verify(chatGenerationStateService, times(2)).updateReferenceMappings(eq("gen-1"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void duplicateChunkKeepsFirstReferenceNumber() {
        chatHandler.mergeReferencesFromSearchTool(
                "gen-2",
                "query",
                "search_knowledge",
                toolResult("search_knowledge", List.of(
                        result("md5-a", 1, "a.txt", "HYBRID"),
                        result("md5-b", 2, "b.txt", "HYBRID")
                ))
        );

        ChatHandler.ReferenceMergeResult graphMerge = chatHandler.mergeReferencesFromSearchTool(
                "gen-2",
                "query",
                "graph_search_knowledge",
                toolResult("graph_search_knowledge", List.of(
                        result("md5-b", 2, "b.txt", "GRAPH"),
                        result("md5-c", 3, "c.txt", "GRAPH")
                ))
        );

        assertEquals(Map.of(1, 2, 2, 3), graphMerge.referenceNumberByToolIndex());
        assertEquals(1, graphMerge.appendedCount());
        assertEquals(1, graphMerge.duplicateCount());
        assertEquals(3, graphMerge.totalReferenceCount());
        assertEquals("md5-b", chatHandler.getReferenceDetail("gen-2", 2).fileMd5());
        assertEquals("HYBRID+GRAPH", chatHandler.getReferenceDetail("gen-2", 2).retrievalMode());
        assertEquals("search_knowledge+graph_search_knowledge", chatHandler.getReferenceDetail("gen-2", 2).sourceTool());
        assertEquals("md5-c", chatHandler.getReferenceDetail("gen-2", 3).fileMd5());
    }

    @Test
    void singleSearchToolStillStartsAtOne() {
        ChatHandler.ReferenceMergeResult merge = chatHandler.mergeReferencesFromSearchTool(
                "gen-3",
                "query",
                "search_knowledge",
                toolResult("search_knowledge", List.of(result("md5-a", 1, "a.txt", null)))
        );

        assertEquals(Map.of(1, 1), merge.referenceNumberByToolIndex());
        assertEquals(1, merge.appendedCount());
        assertEquals(0, merge.duplicateCount());
        assertEquals("md5-a", chatHandler.getReferenceDetail("gen-3", 1).fileMd5());
        assertEquals("HYBRID", chatHandler.getReferenceDetail("gen-3", 1).retrievalMode());
        assertEquals("search_knowledge", chatHandler.getReferenceDetail("gen-3", 1).sourceTool());
    }

    @Test
    void toolDecisionAuditRecordsGraphAfterSearchAndCost() {
        ChatHandler.ToolDecisionAudit audit = new ChatHandler.ToolDecisionAudit(true);

        audit.record("search_knowledge", true, 5, 120L, false, null);
        ChatHandler.ToolDecisionAudit.ToolCallMetric graphMetric =
                audit.record("graph_search_knowledge", true, 2, 38L, audit.hasSearchKnowledge(), null);

        assertEquals(2, audit.totalToolCalls());
        assertEquals(1, audit.searchKnowledgeCalls());
        assertEquals(true, audit.graphCalled());
        assertEquals(true, audit.graphSearchAfterSearch());
        assertEquals(2, audit.graphResultCount());
        assertEquals(38L, audit.graphElapsedMs());
        assertEquals(true, graphMetric.searchKnowledgeBeforeGraph());
    }

    private AgentToolRegistry.ToolExecutionResult toolResult(String toolName, List<SearchResult> results) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", "query");
        data.put("topK", results.size());
        data.put("results", results);
        return new AgentToolRegistry.ToolExecutionResult(toolName, true, "", data);
    }

    private SearchResult result(String fileMd5, Integer chunkId, String fileName, String retrievalMode) {
        return new SearchResult(
                fileMd5,
                chunkId,
                "text " + fileName,
                1.0,
                "1",
                "ORG",
                false,
                fileName,
                1,
                "anchor " + fileName,
                retrievalMode,
                "matched " + fileName
        );
    }
}
