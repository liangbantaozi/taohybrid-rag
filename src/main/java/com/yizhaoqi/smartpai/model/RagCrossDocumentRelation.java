package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "rag_cross_document_relation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_cross_doc_relation_pair_type",
                columnNames = {
                        "source_file_md5",
                        "source_chunk_id",
                        "target_file_md5",
                        "target_chunk_id",
                        "source_entity_id",
                        "target_entity_id",
                        "relation_type"
                }
        ),
        indexes = {
                @Index(name = "idx_rag_cross_doc_source_file", columnList = "source_file_md5"),
                @Index(name = "idx_rag_cross_doc_target_file", columnList = "target_file_md5"),
                @Index(name = "idx_rag_cross_doc_type", columnList = "relation_type"),
                @Index(name = "idx_rag_cross_doc_source_scope", columnList = "source_user_id, source_org_tag, source_is_public"),
                @Index(name = "idx_rag_cross_doc_target_scope", columnList = "target_user_id, target_org_tag, target_is_public")
        }
)
public class RagCrossDocumentRelation {

    public static final String TYPE_SAME_ENTITY = "same_entity_across_documents";
    public static final String BUILD_METHOD_RULES = "RULES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_entity_id", nullable = false)
    private RagEntity sourceEntity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_entity_id", nullable = false)
    private RagEntity targetEntity;

    @Column(name = "relation_type", nullable = false, length = 64)
    private String relationType;

    @Column(name = "relation_description", length = 1000)
    private String relationDescription;

    @Column(name = "source_file_md5", nullable = false, length = 32)
    private String sourceFileMd5;

    @Column(name = "source_chunk_id", nullable = false)
    private Integer sourceChunkId;

    @Column(name = "source_page_number")
    private Integer sourcePageNumber;

    @Column(name = "source_anchor_text", length = 512)
    private String sourceAnchorText;

    @Column(name = "source_evidence_text", columnDefinition = "TEXT")
    private String sourceEvidenceText;

    @Column(name = "target_file_md5", nullable = false, length = 32)
    private String targetFileMd5;

    @Column(name = "target_chunk_id", nullable = false)
    private Integer targetChunkId;

    @Column(name = "target_page_number")
    private Integer targetPageNumber;

    @Column(name = "target_anchor_text", length = 512)
    private String targetAnchorText;

    @Column(name = "target_evidence_text", columnDefinition = "TEXT")
    private String targetEvidenceText;

    @Column(name = "evidence_text", columnDefinition = "TEXT")
    private String evidenceText;

    @Column(nullable = false)
    private double confidence = 0.6d;

    @Column(name = "build_method", nullable = false, length = 32)
    private String buildMethod = BUILD_METHOD_RULES;

    @Column(name = "source_user_id", nullable = false, length = 64)
    private String sourceUserId;

    @Column(name = "source_org_tag", length = 50)
    private String sourceOrgTag;

    @Column(name = "source_is_public", nullable = false)
    private boolean sourcePublic = false;

    @Column(name = "target_user_id", nullable = false, length = 64)
    private String targetUserId;

    @Column(name = "target_org_tag", length = 50)
    private String targetOrgTag;

    @Column(name = "target_is_public", nullable = false)
    private boolean targetPublic = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
