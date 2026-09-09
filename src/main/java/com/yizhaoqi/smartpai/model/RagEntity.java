package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "rag_entity",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_entity_normalized_type", columnNames = {"normalized_name", "type"}),
        indexes = {
                @Index(name = "idx_rag_entity_normalized_name", columnList = "normalized_name"),
                @Index(name = "idx_rag_entity_type", columnList = "type")
        }
)
public class RagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Column(nullable = false, length = 32)
    private String type;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
