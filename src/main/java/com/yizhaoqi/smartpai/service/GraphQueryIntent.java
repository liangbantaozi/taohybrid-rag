package com.yizhaoqi.smartpai.service;

import java.util.List;

public record GraphQueryIntent(
        QueryType queryType,
        boolean needsGraph,
        double confidence,
        List<String> entitiesMentioned,
        String reason,
        RouteSource routeSource,
        long intentClassifyMs,
        boolean intentClassifyCalled,
        int llmIntentTokens
) {
    public static GraphQueryIntent disabled(long elapsedMs) {
        return new GraphQueryIntent(
                QueryType.UNKNOWN,
                false,
                1.0d,
                List.of(),
                "知识图谱参考开关关闭",
                RouteSource.GRAPH_DISABLED,
                elapsedMs,
                false,
                0
        );
    }

    public static GraphQueryIntent fallback(String reason, long elapsedMs) {
        return new GraphQueryIntent(
                QueryType.UNKNOWN,
                false,
                0.4d,
                List.of(),
                reason,
                RouteSource.FALLBACK,
                elapsedMs,
                false,
                0
        );
    }

    public GraphQueryIntent withElapsedMs(long elapsedMs) {
        return new GraphQueryIntent(
                queryType,
                needsGraph,
                confidence,
                entitiesMentioned == null ? List.of() : List.copyOf(entitiesMentioned),
                reason,
                routeSource,
                elapsedMs,
                intentClassifyCalled,
                llmIntentTokens
        );
    }

    public enum QueryType {
        FACTUAL,
        RELATIONSHIP,
        MULTI_HOP,
        AGGREGATION,
        COMPARISON,
        UNKNOWN
    }

    public enum RouteSource {
        RULES,
        ENTITY_LINKING,
        LLM,
        FALLBACK,
        GRAPH_DISABLED
    }
}
