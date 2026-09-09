package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "rag_relation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_relation_file_chunk_pair_type",
                columnNames = {"file_md5", "chunk_id", "source_entity_id", "target_entity_id", "relation_type"}
        ),
        indexes = {
                @Index(name = "idx_rag_relation_file", columnList = "file_md5"),
                @Index(name = "idx_rag_relation_chunk", columnList = "file_md5, chunk_id"),
                @Index(name = "idx_rag_relation_type", columnList = "relation_type"),
                @Index(name = "idx_rag_relation_scope", columnList = "user_id, org_tag, is_public")
        }
)
public class RagRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_entity_id", nullable = false)
    private RagEntity sourceEntity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_entity_id", nullable = false)
    private RagEntity targetEntity;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(name = "evidence_text", columnDefinition = "TEXT")
    private String evidenceText;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
