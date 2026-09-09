package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEvaluationServiceGraphTest {

    private HybridSearchService hybridSearchService;
    private GraphSearchService graphSearchService;
    private GraphQueryIntentService graphQueryIntentService;
    private FileUploadRepository fileUploadRepository;
    private RetrievalEvaluationService service;

    @BeforeEach
    void setUp() {
        hybridSearchService = mock(HybridSearchService.class);
        graphSearchService = mock(GraphSearchService.class);
        graphQueryIntentService = mock(GraphQueryIntentService.class);
        fileUploadRepository = mock(FileUploadRepository.class);
        service = new RetrievalEvaluationService(
                hybridSearchService,
                graphSearchService,
                graphQueryIntentService,
                fileUploadRepository,
                new ObjectMapper(),
                "testdata/rag-eval/questions.v1.jsonl",
                "testdata/rag-eval/questions.hard.v1.jsonl",
                "testdata/rag-eval/questions.graph.v1.jsonl",
                "target/rag-eval-test-results",
                0L
        );

        when(fileUploadRepository.findByUserIdAndFileNameOrderByCreatedAtDesc(eq("2"), anyString()))
                .thenAnswer(invocation -> List.of(file(invocation.getArgument(1))));
        when(hybridSearchService.searchDistinctFilesWithPermission(anyString(), eq("2"), anyInt()))
                .thenAnswer(invocation -> List.of(result(fileMd5ForQuestion(invocation.getArgument(0)), 1)));
        when(graphSearchService.searchWithPermission(anyString(), eq("2"), anyInt()))
                .thenAnswer(invocation -> List.of(result(fileMd5ForQuestion(invocation.getArgument(0)), 2)));
        when(graphQueryIntentService.classify(anyString(), eq("2"), eq(true)))
                .thenAnswer(invocation -> routedIntent(invocation.getArgument(0)));
    }

    @Test
    void graphDatasetEvaluatesGraphStrategiesAndDecisionLabels() throws Exception {
        RetrievalEvaluationService.EvaluationReport report = service.evaluate(
                "2",
                5,
                "graph-v1",
                List.of("HYBRID_ONLY", "GRAPH_ONLY", "HYBRID_PLUS_GRAPH_TOOL", "HYBRID_PLUS_GRAPH_ROUTED")
        );

        assertEquals(8, report.questionCount());
        assertNotNull(report.outputFile());
        assertEquals(4, report.strategies().size());

        RetrievalEvaluationService.StrategyReport hybridPlusGraph = report.strategies().stream()
                .filter(strategy -> "HYBRID_PLUS_GRAPH_TOOL".equals(strategy.strategy()))
                .findFirst()
                .orElseThrow();

        assertEquals(1.0d, hybridPlusGraph.graphCallAccuracy());
        assertTrue(hybridPlusGraph.averageSearchKnowledgeMs() >= 0);
        assertTrue(hybridPlusGraph.averageGraphSearchMs() >= 0);
        assertTrue(hybridPlusGraph.questions().stream().anyMatch(RetrievalEvaluationService.QuestionResult::graphCalled));
        assertTrue(hybridPlusGraph.questions().stream().anyMatch(question -> !question.graphCalled()));

        RetrievalEvaluationService.StrategyReport routed = report.strategies().stream()
                .filter(strategy -> "HYBRID_PLUS_GRAPH_ROUTED".equals(strategy.strategy()))
                .findFirst()
                .orElseThrow();
        assertEquals(1.0d, routed.graphCallAccuracy());
        assertEquals(0.0d, routed.simpleGraphFalsePositiveRate());
        assertEquals(0.0d, routed.complexGraphFalseNegativeRate());
        assertTrue(routed.averageIntentClassifyMs() >= 0);
        assertTrue(routed.averageTotalExtraMs() >= 0);
        assertTrue(routed.questions().stream().anyMatch(RetrievalEvaluationService.QuestionResult::routeNeedsGraph));
        assertTrue(routed.questions().stream().anyMatch(question -> !question.routeNeedsGraph()));
        assertTrue(routed.questions().stream().allMatch(question -> question.recallAtK() >= 0));
        assertTrue(routed.questions().stream().allMatch(question -> question.mrr() >= 0));
    }

    @Test
    void routedSimpleQuestionDoesNotCallGraph() throws Exception {
        String simpleQuestion = "研究生国家奖学金金额是多少？";
        RetrievalEvaluationService.EvaluationQuestion question = new RetrievalEvaluationService.EvaluationQuestion(
                "simple",
                simpleQuestion,
                "",
                List.of("02-graduate-national-scholarship-2024-2025.pdf"),
                "",
                "factual",
                0,
                false
        );
        when(graphQueryIntentService.classify(eq(simpleQuestion), eq("2"), eq(true)))
                .thenReturn(new GraphQueryIntent(
                        GraphQueryIntent.QueryType.FACTUAL,
                        false,
                        0.9d,
                        List.of(),
                        "单点事实",
                        GraphQueryIntent.RouteSource.RULES,
                        3L,
                        false,
                        0
                ));

        RetrievalEvaluationService.EvaluationReport report = service.evaluate(
                "2",
                5,
                "graph-v1",
                List.of("HYBRID_PLUS_GRAPH_ROUTED")
        );

        RetrievalEvaluationService.StrategyReport routed = report.strategies().get(0);
        assertTrue(routed.questions().stream().anyMatch(questionResult ->
                simpleQuestion.equals(questionResult.question())
                        && !questionResult.graphCalled()
                        && !questionResult.simpleGraphFalsePositive()));
        verify(graphSearchService, never()).searchWithPermission(eq(simpleQuestion), eq("2"), anyInt());
    }

    @Test
    void routedGraphQueryFailureFallsBackToHybridResultsAndRecordsReason() throws Exception {
        when(graphSearchService.searchWithPermission(anyString(), eq("2"), anyInt()))
                .thenThrow(new RuntimeException("graph unavailable"));

        RetrievalEvaluationService.EvaluationReport report = service.evaluate(
                "2",
                5,
                "graph-v1",
                List.of("HYBRID_PLUS_GRAPH_ROUTED")
        );

        RetrievalEvaluationService.StrategyReport routed = report.strategies().get(0);
        assertTrue(routed.questions().stream().anyMatch(question ->
                question.graphCalled()
                        && question.graphFallbackReason() != null
                        && question.graphFallbackReason().startsWith("Graph query failed: graph unavailable")));
        assertTrue(routed.questions().stream().allMatch(question -> question.firstRelevantRank() >= 0));
    }

    @Test
    void routedIntentFailureSkipsGraphAndRecordsFallbackReason() throws Exception {
        when(graphQueryIntentService.classify(anyString(), eq("2"), eq(true)))
                .thenThrow(new RuntimeException("route unavailable"));

        RetrievalEvaluationService.EvaluationReport report = service.evaluate(
                "2",
                5,
                "graph-v1",
                List.of("HYBRID_PLUS_GRAPH_ROUTED")
        );

        RetrievalEvaluationService.StrategyReport routed = report.strategies().get(0);
        assertTrue(routed.questions().stream().allMatch(question -> !question.routeNeedsGraph()));
        assertTrue(routed.questions().stream().allMatch(question -> !question.graphCalled()));
        assertTrue(routed.questions().stream().allMatch(question ->
                question.graphFallbackReason() != null
                        && question.graphFallbackReason().startsWith("Graph route failed: route unavailable")));
        verify(graphSearchService, never()).searchWithPermission(anyString(), eq("2"), anyInt());
        assertFalse(routed.questions().isEmpty());
    }

    private FileUpload file(String fileName) {
        FileUpload file = new FileUpload();
        file.setFileName(fileName);
        file.setFileMd5(md5(fileName));
        file.setUserId("2");
        return file;
    }

    private SearchResult result(String fileMd5, int chunkId) {
        return new SearchResult(
                fileMd5,
                chunkId,
                "evidence",
                1.0,
                "2",
                "default",
                true,
                fileName(fileMd5),
                1,
                "anchor",
                "GRAPH".equals(fileMd5) ? "GRAPH" : "HYBRID",
                "matched"
        );
    }

    private String fileMd5ForQuestion(String question) {
        if (question.contains("学业奖学金") || question.contains("奖学金评定")) {
            return md5("01-master-academic-scholarship.pdf");
        }
        if (question.contains("培养方案") || question.contains("学位申请") || question.contains("论文评阅")) {
            return md5("09-master-degree-application-guide-2025.pdf");
        }
        if (question.contains("资格考试")) {
            return md5("03-doctoral-qualification-exam.pdf");
        }
        return md5("02-graduate-national-scholarship-2024-2025.pdf");
    }

    private String md5(String fileName) {
        return "md5-" + fileName;
    }

    private String fileName(String fileMd5) {
        return fileMd5.replaceFirst("^md5-", "");
    }

    private GraphQueryIntent routedIntent(String question) {
        boolean needsGraph = question.contains("关系")
                || question.contains("联系")
                || question.contains("条件")
                || question.contains("差异")
                || question.contains("同时")
                || question.contains("关联")
                || question.contains("影响")
                || question.contains("依赖");
        return new GraphQueryIntent(
                needsGraph ? GraphQueryIntent.QueryType.RELATIONSHIP : GraphQueryIntent.QueryType.FACTUAL,
                needsGraph,
                needsGraph ? 0.88d : 0.82d,
                needsGraph ? List.of("研究生国家奖学金") : List.of(),
                needsGraph ? "复杂题" : "简单题",
                GraphQueryIntent.RouteSource.RULES,
                2L,
                false,
                0
        );
    }
}
