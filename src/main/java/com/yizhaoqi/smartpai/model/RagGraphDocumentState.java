package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "rag_graph_document_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_graph_document_state_file", columnNames = "file_md5"),
        indexes = {
                @Index(name = "idx_rag_graph_document_state_enabled", columnList = "graph_enabled"),
                @Index(name = "idx_rag_graph_document_state_status", columnList = "graph_status"),
                @Index(name = "idx_rag_graph_document_state_scope", columnList = "graph_scope, scope_id")
        }
)
public class RagGraphDocumentState {

    public static final String SCOPE_ENTERPRISE = "ENTERPRISE";
    public static final String SCOPE_ORG = "ORG";
    public static final String SCOPE_PRIVATE = "PRIVATE";

    public static final String STATUS_NOT_BUILT = "NOT_BUILT";
    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "graph_enabled", nullable = false)
    private boolean graphEnabled = false;

    @Column(name = "graph_scope", nullable = false, length = 32)
    private String graphScope = SCOPE_ENTERPRISE;

    @Column(name = "scope_id", length = 100)
    private String scopeId = "enterprise";

    @Column(name = "graph_status", nullable = false, length = 32)
    private String graphStatus = STATUS_NOT_BUILT;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(name = "entity_count", nullable = false)
    private int entityCount = 0;

    @Column(name = "mention_count", nullable = false)
    private int mentionCount = 0;

    @Column(name = "relation_count", nullable = false)
    private int relationCount = 0;

    @Column(name = "build_ms")
    private Long buildMs;

    @Column(name = "last_built_at")
    private LocalDateTime lastBuiltAt;

    @Column(name = "graph_error", length = 1000)
    private String graphError;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
