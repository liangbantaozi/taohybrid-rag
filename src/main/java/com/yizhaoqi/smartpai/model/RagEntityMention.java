package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "rag_entity_mention",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_entity_mention_file_chunk_entity", columnNames = {"file_md5", "chunk_id", "entity_id"}),
        indexes = {
                @Index(name = "idx_rag_entity_mention_file", columnList = "file_md5"),
                @Index(name = "idx_rag_entity_mention_chunk", columnList = "file_md5, chunk_id"),
                @Index(name = "idx_rag_entity_mention_scope", columnList = "user_id, org_tag, is_public")
        }
)
public class RagEntityMention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private RagEntity entity;

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
