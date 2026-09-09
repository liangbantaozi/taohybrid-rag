package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RetrievalEvaluationService {
    private static final Logger logger = LoggerFactory.getLogger(RetrievalEvaluationService.class);
    private static final long EVALUATION_EMBEDDING_DELAY_MS = 2_000L;
    private final HybridSearchService searchService;
    private final GraphSearchService graphSearchService;
    private final GraphQueryIntentService graphQueryIntentService;
    private final FileUploadRepository fileUploadRepository;
    private final ObjectMapper objectMapper;
    private final Path datasetPath;
    private final Path hardDatasetPath;
    private final Path v2DatasetPath;
    private final Path hardV2DatasetPath;
    private final Path graphDatasetPath;
    private final Path generatedGraphDatasetPath;
    private final Path resultDirectory;
    private final long evaluationEmbeddingDelayMs;

    @Autowired
    public RetrievalEvaluationService(HybridSearchService searchService,
                                      GraphSearchService graphSearchService,
                                      GraphQueryIntentService graphQueryIntentService,
                                      FileUploadRepository fileUploadRepository,
                                      ObjectMapper objectMapper,
                                      @Value("${rag.evaluation.dataset-path:testdata/rag-eval/questions.v1.jsonl}") String datasetPath,
                                      @Value("${rag.evaluation.hard-dataset-path:testdata/rag-eval/questions.hard.v1.jsonl}") String hardDatasetPath,
                                      @Value("${rag.evaluation.graph-dataset-path:testdata/rag-eval/questions.graph.v1.jsonl}") String graphDatasetPath,
                                      @Value("${rag.evaluation.embedding-delay-ms:2000}") long evaluationEmbeddingDelayMs,
                                      @Value("${rag.evaluation.result-directory:testdata/rag-eval/results}") String resultDirectory) {
        this(searchService, graphSearchService, graphQueryIntentService, fileUploadRepository, objectMapper, datasetPath, hardDatasetPath, graphDatasetPath, resultDirectory, evaluationEmbeddingDelayMs);
    }

    RetrievalEvaluationService(HybridSearchService searchService,
                               GraphSearchService graphSearchService,
                               GraphQueryIntentService graphQueryIntentService,
                               FileUploadRepository fileUploadRepository,
                               ObjectMapper objectMapper,
                               String datasetPath,
                               String hardDatasetPath,
                               String graphDatasetPath,
                               String resultDirectory,
                               long evaluationEmbeddingDelayMs) {
        this.searchService = searchService;
        this.graphSearchService = graphSearchService;
        this.graphQueryIntentService = graphQueryIntentService;
        this.fileUploadRepository = fileUploadRepository;
        this.objectMapper = objectMapper;
        this.datasetPath = Path.of(datasetPath);
        this.hardDatasetPath = Path.of(hardDatasetPath);
        this.v2DatasetPath = Path.of("testdata/rag-eval/questions.v2.jsonl");
        this.hardV2DatasetPath = Path.of("testdata/rag-eval/questions.hard.v2.jsonl");
        this.graphDatasetPath = Path.of(graphDatasetPath);
        this.generatedGraphDatasetPath = Path.of("testdata/rag-eval/questions.graphrag.generated.v1.jsonl");
        this.resultDirectory = Path.of(resultDirectory);
        this.evaluationEmbeddingDelayMs = Math.max(0L, evaluationEmbeddingDelayMs);
    }

    public EvaluationReport evaluate(String evaluatedUserId, int topK, String dataset, List<String> strategyNames) throws IOException {
        List<EvaluationQuestion> questions = readQuestions(datasetPath(dataset));
        List<StrategyReport> strategies = strategyNames.stream()
                .map(strategy -> runStrategy(strategy, questions, evaluatedUserId, topK))
                .toList();
        EvaluationReport report = new EvaluationReport(OffsetDateTime.now().toString(), evaluatedUserId, topK, questions.size(), strategies);
        Files.createDirectories(resultDirectory);
        Path output = resultDirectory.resolve("retrieval-evaluation-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()) + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return report.withOutputFile(output.toString());
    }

    private Path datasetPath(String dataset) {
        return switch (dataset) {
            case "hard-v1" -> hardDatasetPath;
            case "v2" -> v2DatasetPath;
            case "hard-v2" -> hardV2DatasetPath;
            case "graph-v1" -> graphDatasetPath;
            case "graphrag-generated-v1" -> generatedGraphDatasetPath;
            default -> datasetPath;
        };
    }

    private List<EvaluationQuestion> readQuestions(Path path) throws IOException {
        List<EvaluationQuestion> questions = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) questions.add(objectMapper.readValue(line, EvaluationQuestion.class));
        }
        return questions;
    }

    private StrategyReport runStrategy(String strategy, List<EvaluationQuestion> questions, String userId, int topK) {
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt5 = 0;
        int strictHitsAtK = 0;
        int crossDocumentQuestions = 0;
        int crossDocumentStrictHits = 0;
        int graphExpectedQuestions = 0;
        int graphCorrectDecisions = 0;
        int graphRelevantHits = 0;
        int simpleGraphFalsePositiveCount = 0;
        int simpleGraphQuestionCount = 0;
        int complexGraphFalseNegativeCount = 0;
        int complexGraphQuestionCount = 0;
        int intentClassifyCalledCount = 0;
        double recallAt3Sum = 0;
        double recallAt5Sum = 0;
        double recallAtKSum = 0;
        double reciprocalRankSum = 0;
        long searchKnowledgeMsSum = 0L;
        long graphSearchMsSum = 0L;
        long intentClassifyMsSum = 0L;
        long totalAnswerMsSum = 0L;
        long totalExtraMsSum = 0L;
        int graphResultCountSum = 0;
        int llmIntentTokensSum = 0;
        int graphAddedContextTokensSum = 0;
        List<QuestionResult> details = new ArrayList<>();
        for (EvaluationQuestion question : questions) {
            Set<String> expectedMd5s = resolveExpectedMd5s(question, userId);
            StrategyRunResult runResult = runQuestionStrategy(strategy, question, userId, topK);
            List<SearchResult> results = runResult.results();
            int rank = 0;
            Set<String> retrievedRelevantMd5s = new HashSet<>();
            List<String> returnedFiles = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                returnedFiles.add(results.get(i).getFileName());
                if (expectedMd5s.contains(results.get(i).getFileMd5())) {
                    if (rank == 0) rank = i + 1;
                    retrievedRelevantMd5s.add(results.get(i).getFileMd5());
                }
            }
            boolean anyHitAt1 = rank > 0 && rank <= 1;
            boolean anyHitAt3 = rank > 0 && rank <= 3;
            boolean anyHitAt5 = rank > 0 && rank <= 5;
            double recallAt3 = recallAtLimit(results, expectedMd5s, 3);
            double recallAt5 = recallAtLimit(results, expectedMd5s, 5);
            double recallAtK = recallAtLimit(results, expectedMd5s, topK);
            double reciprocalRank = rank > 0 ? 1d / rank : 0d;
            int relevantCount = retrievedRelevantMd5s.size();
            boolean strictHit = retrievedRelevantMd5s.containsAll(expectedMd5s);
            if (anyHitAt1) hitAt1++;
            if (anyHitAt3) hitAt3++;
            if (anyHitAt5) hitAt5++;
            if (strictHit) strictHitsAtK++;
            recallAt3Sum += recallAt3;
            recallAt5Sum += recallAt5;
            recallAtKSum += recallAtK;
            reciprocalRankSum += reciprocalRank;
            boolean crossDocument = isCrossDocumentQuestion(question, expectedMd5s);
            if (crossDocument) {
                crossDocumentQuestions++;
                if (strictHit) {
                    crossDocumentStrictHits++;
                }
            }
            Boolean graphCallExpected = expectedGraphCall(question);
            Boolean graphCallCorrect = null;
            if (graphCallExpected != null) {
                graphExpectedQuestions++;
                graphCallCorrect = graphCallExpected == runResult.graphCalled();
                if (graphCallCorrect) {
                    graphCorrectDecisions++;
                }
                if (!graphCallExpected) {
                    simpleGraphQuestionCount++;
                    if (runResult.graphCalled()) {
                        simpleGraphFalsePositiveCount++;
                    }
                } else {
                    complexGraphQuestionCount++;
                    if (!runResult.graphCalled()) {
                        complexGraphFalseNegativeCount++;
                    }
                }
            }
            boolean graphEvidenceHit = runResult.graphCalled() && graphResultHits(runResult.graphResults(), expectedMd5s);
            if (graphEvidenceHit) {
                graphRelevantHits++;
            }
            searchKnowledgeMsSum += runResult.searchKnowledgeMs();
            graphSearchMsSum += runResult.graphSearchMs();
            intentClassifyMsSum += runResult.intentClassifyMs();
            if (runResult.intentClassifyCalled()) {
                intentClassifyCalledCount++;
            }
            llmIntentTokensSum += runResult.llmIntentTokens();
            graphAddedContextTokensSum += runResult.graphAddedContextTokens();
            totalAnswerMsSum += runResult.totalAnswerMs();
            totalExtraMsSum += runResult.totalExtraMs();
            graphResultCountSum += runResult.graphResultCount();
            details.add(new QuestionResult(
                    question.id(),
                    question.question(),
                    question.type(),
                    question.hopCount(),
                    question.expectedSourceFiles(),
                    returnedFiles,
                    rank,
                    relevantCount,
                    strictHit,
                    recallAtK,
                    reciprocalRank,
                    anyHitAt1,
                    anyHitAt3,
                    anyHitAt5,
                    recallAt3,
                    recallAt5,
                    runResult.searchKnowledgeMs(),
                    runResult.graphSearchMs(),
                    runResult.totalAnswerMs(),
                    runResult.toolCallCount(),
                    runResult.graphCalled(),
                    graphCallExpected,
                    graphCallCorrect,
                    runResult.routeNeedsGraph(),
                    runResult.routeSource(),
                    simpleGraphFalsePositive(graphCallExpected, runResult.graphCalled()),
                    complexGraphFalseNegative(graphCallExpected, runResult.graphCalled()),
                    graphEvidenceHit,
                    runResult.searchBeforeGraph(),
                    runResult.graphResultCount(),
                    runResult.intentClassifyMs(),
                    runResult.intentClassifyCalled(),
                    runResult.llmIntentTokens(),
                    runResult.graphAddedContextTokens(),
                    runResult.totalExtraMs(),
                    runResult.graphFallbackReason()
            ));
            if (usesEmbedding(strategy)) {
                waitForEmbeddingRateLimit();
            }
        }
        int questionCount = questions.size();
        double hitRateAtK = questionCount == 0 ? 0 : (double) strictHitsAtK / questionCount;
        return new StrategyReport(
                strategy,
                hitRateAtK,
                questionCount == 0 ? 0 : recallAtKSum / questionCount,
                questionCount == 0 ? 0 : reciprocalRankSum / questionCount,
                questionCount == 0 ? 0 : (double) hitAt1 / questionCount,
                questionCount == 0 ? 0 : (double) hitAt3 / questionCount,
                questionCount == 0 ? 0 : (double) hitAt5 / questionCount,
                questionCount == 0 ? 0 : recallAt3Sum / questionCount,
                questionCount == 0 ? 0 : recallAt5Sum / questionCount,
                crossDocumentQuestions == 0 ? null : (double) crossDocumentStrictHits / crossDocumentQuestions,
                graphExpectedQuestions == 0 ? null : (double) graphCorrectDecisions / graphExpectedQuestions,
                simpleGraphQuestionCount == 0 ? null : (double) simpleGraphFalsePositiveCount / simpleGraphQuestionCount,
                complexGraphQuestionCount == 0 ? null : (double) complexGraphFalseNegativeCount / complexGraphQuestionCount,
                questionCount == 0 ? 0 : (double) searchKnowledgeMsSum / questionCount,
                questionCount == 0 ? 0 : (double) graphSearchMsSum / questionCount,
                questionCount == 0 ? 0 : (double) intentClassifyMsSum / questionCount,
                questionCount == 0 ? 0 : (double) intentClassifyCalledCount / questionCount,
                questionCount == 0 ? 0 : (double) llmIntentTokensSum / questionCount,
                questionCount == 0 ? 0 : (double) graphAddedContextTokensSum / questionCount,
                questionCount == 0 ? 0 : (double) totalAnswerMsSum / questionCount,
                questionCount == 0 ? 0 : (double) totalExtraMsSum / questionCount,
                questionCount == 0 ? 0 : (double) graphResultCountSum / questionCount,
                questionCount == 0 ? 0 : (double) graphRelevantHits / questionCount,
                details
        );
    }

    private StrategyRunResult runQuestionStrategy(String strategy, EvaluationQuestion question, String userId, int topK) {
        return switch (strategy) {
            case "VECTOR" -> runHybridOnly(() -> searchService.searchVectorDistinctFilesWithPermission(question.question(), userId, topK));
            case "BM25" -> runHybridOnly(() -> searchService.searchBm25DistinctFilesWithPermission(question.question(), userId, topK));
            case "HYBRID_RRF" -> runHybridOnly(() -> searchService.searchRrfDistinctFilesWithPermission(question.question(), userId, topK));
            case "GRAPH_ONLY" -> runGraphOnly(question, userId, topK);
            case "HYBRID_PLUS_GRAPH_TOOL" -> runHybridPlusGraphTool(question, userId, topK);
            case "HYBRID_PLUS_GRAPH_ROUTED" -> runHybridPlusGraphRouted(question, userId, topK);
            case "HYBRID_ONLY", "HYBRID" -> runHybridOnly(() -> searchService.searchDistinctFilesWithPermission(question.question(), userId, topK));
            default -> throw new IllegalArgumentException("unknown strategy: " + strategy);
        };
    }

    private StrategyRunResult runHybridOnly(SearchInvocation invocation) {
        long startedAt = System.nanoTime();
        List<SearchResult> results = invocation.search();
        long searchMs = elapsedMs(startedAt);
        return new StrategyRunResult(results, List.of(), searchMs, 0L, 0L, searchMs, 0L, 1,
                false, false, 0, false, "GRAPH_DISABLED", false, 0, 0, null);
    }

    private StrategyRunResult runGraphOnly(EvaluationQuestion question, String userId, int topK) {
        SafeGraphSearchResult graphSearch = searchGraphSafely(question.question(), userId, topK);
        List<SearchResult> results = graphSearch.results();
        long graphMs = graphSearch.elapsedMs();
        return new StrategyRunResult(results, results, 0L, graphMs, 0L, graphMs, graphMs, 1,
                true, false, results.size(), true, "GRAPH_ONLY", false, 0, estimateGraphContextTokens(results),
                graphSearch.fallbackReason());
    }

    private StrategyRunResult runHybridPlusGraphTool(EvaluationQuestion question, String userId, int topK) {
        long totalStartedAt = System.nanoTime();
        long searchStartedAt = System.nanoTime();
        List<SearchResult> hybridResults = searchService.searchDistinctFilesWithPermission(question.question(), userId, topK);
        long searchMs = elapsedMs(searchStartedAt);
        boolean graphCalled = shouldCallGraph(question);
        if (!graphCalled) {
            return new StrategyRunResult(hybridResults, List.of(), searchMs, 0L, 0L, elapsedMs(totalStartedAt), 0L, 1,
                    false, false, 0, false, "LEGACY_TOOL_RULE", false, 0, 0,
                    "Graph skipped by legacy tool rule");
        }

        SafeGraphSearchResult graphSearch = searchGraphSafely(question.question(), userId, topK);
        List<SearchResult> graphResults = graphSearch.results();
        long graphMs = graphSearch.elapsedMs();
        List<SearchResult> mergedResults = mergeByChunk(hybridResults, graphResults, topK);
        return new StrategyRunResult(mergedResults, graphResults, searchMs, graphMs, 0L, elapsedMs(totalStartedAt), graphMs, 2,
                true, true, graphResults.size(), true, "LEGACY_TOOL_RULE", false, 0, estimateGraphContextTokens(graphResults),
                graphSearch.fallbackReason());
    }

    private StrategyRunResult runHybridPlusGraphRouted(EvaluationQuestion question, String userId, int topK) {
        long totalStartedAt = System.nanoTime();
        SafeGraphIntent safeIntent = classifyGraphIntentSafely(question.question(), userId);
        GraphQueryIntent intent = safeIntent.intent();

        long searchStartedAt = System.nanoTime();
        List<SearchResult> hybridResults = searchService.searchDistinctFilesWithPermission(question.question(), userId, topK);
        long searchMs = elapsedMs(searchStartedAt);

        if (!intent.needsGraph()) {
            return new StrategyRunResult(hybridResults, List.of(), searchMs, 0L, intent.intentClassifyMs(),
                    elapsedMs(totalStartedAt), intent.intentClassifyMs(), 1, false, false, 0,
                    false, intent.routeSource().name(), intent.intentClassifyCalled(), intent.llmIntentTokens(), 0,
                    safeIntent.fallbackReason() != null ? safeIntent.fallbackReason() : "Graph skipped: " + intent.reason());
        }

        SafeGraphSearchResult graphSearch = searchGraphSafely(question.question(), userId, topK);
        List<SearchResult> graphResults = graphSearch.results();
        long graphMs = graphSearch.elapsedMs();
        List<SearchResult> mergedResults = mergeByChunk(hybridResults, graphResults, topK);
        return new StrategyRunResult(mergedResults, graphResults, searchMs, graphMs, intent.intentClassifyMs(),
                elapsedMs(totalStartedAt), intent.intentClassifyMs() + graphMs, 2, true, true, graphResults.size(),
                true, intent.routeSource().name(), intent.intentClassifyCalled(), intent.llmIntentTokens(), estimateGraphContextTokens(graphResults),
                graphSearch.fallbackReason());
    }

    private SafeGraphIntent classifyGraphIntentSafely(String question, String userId) {
        long startedAt = System.nanoTime();
        try {
            return new SafeGraphIntent(graphQueryIntentService.classify(question, userId, true), null);
        } catch (Exception e) {
            logger.warn("Graph 评测路由失败，回退为不调用 Graph: userId={}, question={}", userId, question, e);
            return new SafeGraphIntent(
                    GraphQueryIntent.fallback("Graph 路由异常，评测回退普通知识库检索", elapsedMs(startedAt)),
                    "Graph route failed: " + safeMessage(e)
            );
        }
    }

    private SafeGraphSearchResult searchGraphSafely(String question, String userId, int topK) {
        long startedAt = System.nanoTime();
        try {
            return new SafeGraphSearchResult(
                    safeList(graphSearchService.searchWithPermission(question, userId, topK)),
                    elapsedMs(startedAt),
                    null
            );
        } catch (Exception e) {
            logger.warn("Graph 评测查询失败，返回空 Graph 结果: userId={}, question={}", userId, question, e);
            return new SafeGraphSearchResult(List.of(), elapsedMs(startedAt), "Graph query failed: " + safeMessage(e));
        }
    }

    private List<SearchResult> mergeByChunk(List<SearchResult> hybridResults, List<SearchResult> graphResults, int topK) {
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchResult result : concat(hybridResults, graphResults)) {
            if (result == null || result.getFileMd5() == null) {
                continue;
            }
            String key = result.getFileMd5() + "\u0000" + result.getChunkId();
            if (seen.add(key)) {
                merged.add(result);
            }
            if (merged.size() >= topK) {
                break;
            }
        }
        return merged;
    }

    private List<SearchResult> concat(List<SearchResult> first, List<SearchResult> second) {
        List<SearchResult> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }

    private boolean shouldCallGraph(EvaluationQuestion question) {
        String type = question.type() == null ? "" : question.type().toLowerCase();
        String query = question.question() == null ? "" : question.question();
        if (type.contains("cross") || type.contains("relationship") || type.contains("condition")
                || type.contains("comparison") || type.contains("scope") || type.contains("chain")) {
            return true;
        }
        return containsAny(query, "关系", "联系", "关联", "影响", "依赖", "条件链", "对比", "差异", "不同",
                "适用", "范围", "对象", "满足哪些条件", "哪些条件", "之间", "同时");
    }

    private Boolean expectedGraphCall(EvaluationQuestion question) {
        if (question.graphCallExpected() != null) {
            return question.graphCallExpected();
        }

        String type = question.type() == null ? "" : question.type().toLowerCase();
        if (type.contains("factual") || type.contains("simple_fact")) {
            return false;
        }
        if (type.contains("multi") || type.contains("aggregation") || type.contains("relationship")
                || type.contains("condition") || type.contains("comparison") || type.contains("cross")
                || type.contains("scope") || type.contains("chain")) {
            return true;
        }
        Integer hopCount = parseHopCount(question.hopCount());
        if (hopCount != null && hopCount > 0) {
            return true;
        }
        return null;
    }

    private Integer parseHopCount(Object hopCount) {
        if (hopCount instanceof Number number) {
            return number.intValue();
        }
        if (hopCount instanceof String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Set<String> resolveExpectedMd5s(EvaluationQuestion question, String userId) {
        Set<String> expectedMd5s = new HashSet<>();
        for (String expectedFile : safeList(question.expectedSourceFiles())) {
            expectedMd5s.add(fileUploadRepository.findByUserIdAndFileNameOrderByCreatedAtDesc(userId, expectedFile)
                    .stream().findFirst().map(FileUpload::getFileMd5)
                    .orElseThrow(() -> new IllegalStateException("Evaluation source is not uploaded: " + expectedFile)));
        }
        return expectedMd5s;
    }

    private double recallAtLimit(List<SearchResult> results, Set<String> expectedMd5s, int limit) {
        if (expectedMd5s == null || expectedMd5s.isEmpty()) {
            return 0;
        }
        Set<String> retrievedRelevantMd5s = new HashSet<>();
        for (SearchResult result : safeList(results).stream().limit(Math.max(limit, 0)).toList()) {
            if (expectedMd5s.contains(result.getFileMd5())) {
                retrievedRelevantMd5s.add(result.getFileMd5());
            }
        }
        return (double) retrievedRelevantMd5s.size() / expectedMd5s.size();
    }

    private boolean graphResultHits(List<SearchResult> graphResults, Set<String> expectedMd5s) {
        if (graphResults == null || expectedMd5s == null || expectedMd5s.isEmpty()) {
            return false;
        }
        return graphResults.stream().anyMatch(result -> expectedMd5s.contains(result.getFileMd5()));
    }

    private boolean simpleGraphFalsePositive(Boolean graphCallExpected, boolean graphCalled) {
        return Boolean.FALSE.equals(graphCallExpected) && graphCalled;
    }

    private boolean complexGraphFalseNegative(Boolean graphCallExpected, boolean graphCalled) {
        return Boolean.TRUE.equals(graphCallExpected) && !graphCalled;
    }

    private int estimateGraphContextTokens(List<SearchResult> graphResults) {
        int chars = 0;
        for (SearchResult result : safeList(graphResults)) {
            String text = result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent();
            if (text != null) {
                chars += text.length();
            }
        }
        return Math.max(0, (int) Math.ceil(chars / 4.0d));
    }

    private boolean isCrossDocumentQuestion(EvaluationQuestion question, Set<String> expectedMd5s) {
        if (expectedMd5s != null && expectedMd5s.size() > 1) {
            return true;
        }
        String type = question.type() == null ? "" : question.type().toLowerCase();
        return type.contains("cross");
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "unknown";
        }
        return e.getMessage();
    }

    private boolean usesEmbedding(String strategy) {
        return "VECTOR".equals(strategy)
                || "HYBRID".equals(strategy)
                || "HYBRID_ONLY".equals(strategy)
                || "HYBRID_RRF".equals(strategy)
                || "HYBRID_PLUS_GRAPH_TOOL".equals(strategy)
                || "HYBRID_PLUS_GRAPH_ROUTED".equals(strategy);
    }

    private void waitForEmbeddingRateLimit() {
        if (evaluationEmbeddingDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(evaluationEmbeddingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retrieval evaluation interrupted", e);
        }
    }

    public record EvaluationQuestion(
            String id,
            String question,
            String answer,
            @JsonAlias("source_documents")
            @JsonProperty("expected_source_files") List<String> expectedSourceFiles,
            String evidence,
            @JsonAlias("category")
            String type,
            @JsonProperty("hop_count") Object hopCount,
            @JsonProperty("graph_call_expected") Boolean graphCallExpected
    ) {}
    public record QuestionResult(String id,
                                 String question,
                                 String category,
                                 Object hopCount,
                                 List<String> expectedSourceFiles,
                                 List<String> returnedFiles,
                                 int firstRelevantRank,
                                 int retrievedRelevantCount,
                                 boolean strictHit,
                                 double recallAtK,
                                 double mrr,
                                 boolean hitAt1,
                                 boolean hitAt3,
                                 boolean hitAt5,
                                 double recallAt3,
                                 double recallAt5,
                                 long searchKnowledgeMs,
                                 long graphSearchMs,
                                 long totalAnswerMs,
                                 int toolCallCount,
                                 boolean graphCalled,
                                 Boolean graphCallExpected,
                                 Boolean graphCallCorrect,
                                 boolean routeNeedsGraph,
                                 String routeSource,
                                 boolean simpleGraphFalsePositive,
                                 boolean complexGraphFalseNegative,
                                 boolean graphEvidenceHit,
                                 boolean searchBeforeGraph,
                                 int graphResultCount,
                                 long intentClassifyMs,
                                 boolean intentClassifyCalled,
                                 int llmIntentTokens,
                                 int graphAddedContextTokens,
                                 long totalExtraMs,
                                 String graphFallbackReason) {}
    public record StrategyReport(String strategy,
                                 double hitRateAtK,
                                 double recallAtK,
                                 double mrr,
                                 double hitAt1,
                                 double hitAt3,
                                 double hitAt5,
                                 double recallAt3,
                                 double recallAt5,
                                 Double crossDocumentStrictHitRate,
                                 Double graphCallAccuracy,
                                 Double simpleGraphFalsePositiveRate,
                                 Double complexGraphFalseNegativeRate,
                                 double averageSearchKnowledgeMs,
                                 double averageGraphSearchMs,
                                 double averageIntentClassifyMs,
                                 double intentClassifyCallRate,
                                 double averageLlmIntentTokens,
                                 double averageGraphAddedContextTokens,
                                 double averageTotalAnswerMs,
                                 double averageTotalExtraMs,
                                 double averageGraphResultCount,
                                 double graphEvidenceHitRate,
                                 List<QuestionResult> questions) {}
    public record EvaluationReport(String executedAt, String evaluatedUserId, int topK, int questionCount, List<StrategyReport> strategies, String outputFile) {
        public EvaluationReport(String executedAt, String evaluatedUserId, int topK, int questionCount, List<StrategyReport> strategies) { this(executedAt, evaluatedUserId, topK, questionCount, strategies, null); }
        public EvaluationReport withOutputFile(String outputFile) { return new EvaluationReport(executedAt, evaluatedUserId, topK, questionCount, strategies, outputFile); }
    }

    private record StrategyRunResult(List<SearchResult> results,
                                     List<SearchResult> graphResults,
                                     long searchKnowledgeMs,
                                     long graphSearchMs,
                                     long intentClassifyMs,
                                     long totalAnswerMs,
                                     long totalExtraMs,
                                     int toolCallCount,
                                     boolean graphCalled,
                                     boolean searchBeforeGraph,
                                     int graphResultCount,
                                     boolean routeNeedsGraph,
                                     String routeSource,
                                     boolean intentClassifyCalled,
                                     int llmIntentTokens,
                                     int graphAddedContextTokens,
                                     String graphFallbackReason) {}

    private record SafeGraphIntent(GraphQueryIntent intent, String fallbackReason) {}

    private record SafeGraphSearchResult(List<SearchResult> results, long elapsedMs, String fallbackReason) {}

    @FunctionalInterface
    private interface SearchInvocation {
        List<SearchResult> search();
    }
}
