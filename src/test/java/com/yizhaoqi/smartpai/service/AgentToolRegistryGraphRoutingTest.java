package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolRegistryGraphRoutingTest {

    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry(
                mock(HybridSearchService.class),
                mock(GraphSearchService.class),
                mock(DeepSeekClient.class),
                mock(StringRedisTemplate.class),
                mock(ElasticsearchClient.class),
                mock(FileUploadRepository.class),
                "test-index"
        );
    }

    @Test
    void closedSwitchDoesNotExposeGraphTool() {
        List<AgentToolRegistry.AgentTool> tools = registry.getTools(false, intent(true));

        assertFalse(hasGraphTool(tools));
    }

    @Test
    void openSwitchWithRouteFalseDoesNotExposeGraphTool() {
        List<AgentToolRegistry.AgentTool> tools = registry.getTools(true, intent(false));

        assertFalse(hasGraphTool(tools));
    }

    @Test
    void openSwitchWithRouteTrueExposesGraphTool() {
        List<AgentToolRegistry.AgentTool> tools = registry.getTools(true, intent(true));

        assertTrue(hasGraphTool(tools));
    }

    private boolean hasGraphTool(List<AgentToolRegistry.AgentTool> tools) {
        return tools.stream().anyMatch(tool -> "graph_search_knowledge".equals(tool.name()));
    }

    private GraphQueryIntent intent(boolean needsGraph) {
        return new GraphQueryIntent(
                needsGraph ? GraphQueryIntent.QueryType.RELATIONSHIP : GraphQueryIntent.QueryType.FACTUAL,
                needsGraph,
                0.8d,
                List.of(),
                "test",
                GraphQueryIntent.RouteSource.RULES,
                1L,
                false,
                0
        );
    }
}
