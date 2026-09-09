package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.RagEntityRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphQueryIntentServiceTest {

    private RagEntityRepository entityRepository;
    private UserRepository userRepository;
    private OrgTagCacheService orgTagCacheService;
    private GraphQueryIntentService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(RagEntityRepository.class);
        userRepository = mock(UserRepository.class);
        orgTagCacheService = mock(OrgTagCacheService.class);
        service = new GraphQueryIntentService(entityRepository, userRepository, orgTagCacheService);
    }

    @Test
    void disabledSwitchReturnsFalseWithoutEntityLookup() {
        GraphQueryIntent intent = service.classify("奖学金和培养方案有什么关系？", "2", false);

        assertFalse(intent.needsGraph());
        assertEquals(GraphQueryIntent.RouteSource.GRAPH_DISABLED, intent.routeSource());
        verifyNoInteractions(entityRepository, userRepository, orgTagCacheService);
    }

    @Test
    void relationshipRuleRoutesToGraph() {
        GraphQueryIntent intent = service.classify("奖学金评定和培养方案中的学业要求之间有什么联系？", "2", true);

        assertTrue(intent.needsGraph());
        assertEquals(GraphQueryIntent.QueryType.RELATIONSHIP, intent.queryType());
        assertEquals(GraphQueryIntent.RouteSource.RULES, intent.routeSource());
        verifyNoInteractions(entityRepository, userRepository, orgTagCacheService);
    }

    @Test
    void simpleFactRuleDoesNotRouteToGraph() {
        GraphQueryIntent intent = service.classify("研究生国家奖学金金额是多少？", "2", true);

        assertFalse(intent.needsGraph());
        assertEquals(GraphQueryIntent.QueryType.FACTUAL, intent.queryType());
        assertEquals(GraphQueryIntent.RouteSource.RULES, intent.routeSource());
        verifyNoInteractions(entityRepository, userRepository, orgTagCacheService);
    }

    @Test
    void knowledgeBypassDoesNotRouteToGraph() {
        GraphQueryIntent intent = service.classify("不要查知识库，写一首关于奖学金和青春的诗", "2", true);

        assertFalse(intent.needsGraph());
        assertEquals(GraphQueryIntent.RouteSource.RULES, intent.routeSource());
        verifyNoInteractions(entityRepository, userRepository, orgTagCacheService);
    }

    @Test
    void softRelationshipWithAccessibleEntityRoutesToGraph() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of(entity("研究生国家奖学金")));

        GraphQueryIntent intent = service.classify("研究生国家奖学金和培养方案要一起看吗？", "2", true);

        assertTrue(intent.needsGraph());
        assertEquals(GraphQueryIntent.RouteSource.ENTITY_LINKING, intent.routeSource());
        assertEquals(List.of("研究生国家奖学金"), intent.entitiesMentioned());
        verify(userRepository).findById(2L);
    }

    @Test
    void softRelationshipWithoutEntityFallsBackToSearchOnly() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenReturn(List.of());

        GraphQueryIntent intent = service.classify("这个主题和安排要一起看吗？", "2", true);

        assertFalse(intent.needsGraph());
        assertEquals(GraphQueryIntent.RouteSource.FALLBACK, intent.routeSource());
        verify(entityRepository, never()).searchAccessibleEntities(eq(""), anyString(), anyString(), any(), any(Pageable.class));
    }

    @Test
    void entityLinkingFailureFallsBackToSearchOnly() {
        User user = new User();
        user.setId(2L);
        user.setUsername("taozi");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("taozi")).thenReturn(List.of("TEAM_A"));
        when(entityRepository.searchAccessibleEntities(anyString(), anyString(), eq("2"), eq(List.of("TEAM_A")), any(Pageable.class)))
                .thenThrow(new RuntimeException("entity lookup failed"));

        GraphQueryIntent intent = service.classify("研究生国家奖学金和培养方案要一起看吗？", "2", true);

        assertFalse(intent.needsGraph());
        assertEquals(GraphQueryIntent.RouteSource.FALLBACK, intent.routeSource());
        assertEquals("路由失败，回退普通知识库检索", intent.reason());
    }

    private RagEntity entity(String name) {
        RagEntity entity = new RagEntity();
        entity.setId(1L);
        entity.setName(name);
        entity.setNormalizedName(name);
        entity.setType("policy");
        return entity;
    }
}
