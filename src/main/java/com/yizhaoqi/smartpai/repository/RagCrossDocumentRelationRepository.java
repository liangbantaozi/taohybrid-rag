package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface RagCrossDocumentRelationRepository extends JpaRepository<RagCrossDocumentRelation, Long> {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from RagCrossDocumentRelation r
            where r.sourceFileMd5 = :fileMd5
               or r.targetFileMd5 = :fileMd5
            """)
    int deleteByAnyFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RagCrossDocumentRelation r
            set r.sourceUserId = :userId,
                r.sourceOrgTag = :orgTag,
                r.sourcePublic = :isPublic
            where r.sourceFileMd5 = :fileMd5
            """)
    int updateSourcePermissionByFileMd5(@Param("fileMd5") String fileMd5,
                                        @Param("userId") String userId,
                                        @Param("orgTag") String orgTag,
                                        @Param("isPublic") boolean isPublic);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RagCrossDocumentRelation r
            set r.targetUserId = :userId,
                r.targetOrgTag = :orgTag,
                r.targetPublic = :isPublic
            where r.targetFileMd5 = :fileMd5
            """)
    int updateTargetPermissionByFileMd5(@Param("fileMd5") String fileMd5,
                                        @Param("userId") String userId,
                                        @Param("orgTag") String orgTag,
                                        @Param("isPublic") boolean isPublic);

    @Query("""
            select r
            from RagCrossDocumentRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceFileMd5 = :fileMd5 or r.targetFileMd5 = :fileMd5)
              and (r.sourceUserId = :userId or r.sourcePublic = true or r.sourceOrgTag in :orgTags)
              and (r.targetUserId = :userId or r.targetPublic = true or r.targetOrgTag in :orgTags)
            order by r.id asc
            """)
    List<RagCrossDocumentRelation> findByFileMd5WithPermission(@Param("fileMd5") String fileMd5,
                                                               @Param("userId") String userId,
                                                               @Param("orgTags") List<String> orgTags);

    @Query("""
            select r
            from RagCrossDocumentRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceEntity.id = :entityId or r.targetEntity.id = :entityId)
              and (r.sourceUserId = :userId or r.sourcePublic = true or r.sourceOrgTag in :orgTags)
              and (r.targetUserId = :userId or r.targetPublic = true or r.targetOrgTag in :orgTags)
            order by r.id asc
            """)
    List<RagCrossDocumentRelation> findByEntityIdWithPermission(@Param("entityId") Long entityId,
                                                                @Param("userId") String userId,
                                                                @Param("orgTags") List<String> orgTags);

    @Query("""
            select r
            from RagCrossDocumentRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceEntity.id in :entityIds or r.targetEntity.id in :entityIds)
              and (r.sourceUserId = :userId or r.sourcePublic = true or r.sourceOrgTag in :orgTags)
              and (r.targetUserId = :userId or r.targetPublic = true or r.targetOrgTag in :orgTags)
            order by r.id desc
            """)
    List<RagCrossDocumentRelation> findByAnyEntityIdsWithPermission(@Param("entityIds") Collection<Long> entityIds,
                                                                    @Param("userId") String userId,
                                                                    @Param("orgTags") List<String> orgTags,
                                                                    Pageable pageable);

    @Query("""
            select r
            from RagCrossDocumentRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceUserId = :userId or r.sourcePublic = true or r.sourceOrgTag in :orgTags)
              and (r.targetUserId = :userId or r.targetPublic = true or r.targetOrgTag in :orgTags)
              and (
                  lower(r.evidenceText) like lower(concat('%', :query, '%'))
                  or lower(r.sourceEvidenceText) like lower(concat('%', :query, '%'))
                  or lower(r.targetEvidenceText) like lower(concat('%', :query, '%'))
                  or lower(r.relationDescription) like lower(concat('%', :query, '%'))
                  or lower(r.relationType) like lower(concat('%', :query, '%'))
              )
            order by r.id desc
            """)
    List<RagCrossDocumentRelation> searchEvidenceWithPermission(@Param("query") String query,
                                                                @Param("userId") String userId,
                                                                @Param("orgTags") List<String> orgTags,
                                                                Pageable pageable);
}
