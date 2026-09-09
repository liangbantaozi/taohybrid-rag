package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.RetrievalEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/retrieval-evaluations")
public class RetrievalEvaluationController {
    private final RetrievalEvaluationService retrievalEvaluationService;

    public RetrievalEvaluationController(RetrievalEvaluationService retrievalEvaluationService) {
        this.retrievalEvaluationService = retrievalEvaluationService;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam(defaultValue = "2") String userId,
                                 @RequestParam(defaultValue = "5") int topK,
                                 @RequestParam(defaultValue = "v1") String dataset,
                                 @RequestParam(required = false) String strategy) {
        if (topK < 1 || topK > 20) return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "topK must be between 1 and 20"));
        try {
            if (!"v1".equals(dataset) && !"hard-v1".equals(dataset) && !"v2".equals(dataset) && !"hard-v2".equals(dataset) && !"graph-v1".equals(dataset) && !"graphrag-generated-v1".equals(dataset)) return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "unknown dataset"));
            List<String> strategies = strategy == null || strategy.isBlank()
                    ? defaultStrategies(dataset)
                    : List.of(strategy.toUpperCase());
            if (strategies.stream().anyMatch(item -> !List.of("VECTOR", "BM25", "HYBRID", "HYBRID_RRF", "HYBRID_ONLY", "GRAPH_ONLY", "HYBRID_PLUS_GRAPH_TOOL", "HYBRID_PLUS_GRAPH_ROUTED").contains(item))) return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "unknown strategy"));
            return ResponseEntity.ok(Map.of("code", 200, "data", retrievalEvaluationService.evaluate(userId, topK, dataset, strategies)));
        } catch (Exception error) {
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", error.getMessage()));
        }
    }

    private List<String> defaultStrategies(String dataset) {
        if ("graph-v1".equals(dataset) || "graphrag-generated-v1".equals(dataset)) {
            return List.of("HYBRID_ONLY", "HYBRID_PLUS_GRAPH_ROUTED", "HYBRID_PLUS_GRAPH_TOOL", "GRAPH_ONLY");
        }
        return List.of("VECTOR", "BM25", "HYBRID");
    }
}
