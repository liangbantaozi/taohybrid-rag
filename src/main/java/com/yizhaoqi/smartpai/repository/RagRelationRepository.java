package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagRelation;
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
public interface RagRelationRepository extends JpaRepository<RagRelation, Long> {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RagRelation r where r.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RagRelation r
            set r.userId = :userId,
                r.orgTag = :orgTag,
                r.isPublic = :isPublic
            where r.fileMd5 = :fileMd5
            """)
    int updatePermissionByFileMd5(@Param("fileMd5") String fileMd5,
                                  @Param("userId") String userId,
                                  @Param("orgTag") String orgTag,
                                  @Param("isPublic") boolean isPublic);

    @Query("""
            select r
            from RagRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where r.fileMd5 = :fileMd5
              and (r.userId = :userId or r.isPublic = true or r.orgTag in :orgTags)
            order by r.chunkId asc, r.id asc
            """)
    List<RagRelation> findByFileMd5WithPermission(@Param("fileMd5") String fileMd5,
                                                  @Param("userId") String userId,
                                                  @Param("orgTags") List<String> orgTags);

    @Query("""
            select r
            from RagRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceEntity.id = :entityId or r.targetEntity.id = :entityId)
              and (r.userId = :userId or r.isPublic = true or r.orgTag in :orgTags)
            order by r.fileMd5 asc, r.chunkId asc, r.id asc
            """)
    List<RagRelation> findByEntityIdWithPermission(@Param("entityId") Long entityId,
                                                   @Param("userId") String userId,
                                                   @Param("orgTags") List<String> orgTags);

    @Query("""
            select r
            from RagRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.sourceEntity.id in :entityIds or r.targetEntity.id in :entityIds)
              and (r.userId = :userId or r.isPublic = true or r.orgTag in :orgTags)
            order by r.id desc
            """)
    List<RagRelation> findByAnyEntityIdsWithPermission(@Param("entityIds") Collection<Long> entityIds,
                                                       @Param("userId") String userId,
                                                       @Param("orgTags") List<String> orgTags,
                                                       Pageable pageable);

    @Query("""
            select r
            from RagRelation r
            join fetch r.sourceEntity
            join fetch r.targetEntity
            where (r.userId = :userId or r.isPublic = true or r.orgTag in :orgTags)
              and (
                  lower(r.evidenceText) like lower(concat('%', :query, '%'))
                  or lower(r.anchorText) like lower(concat('%', :query, '%'))
                  or lower(r.relationType) like lower(concat('%', :query, '%'))
              )
            order by r.id desc
            """)
    List<RagRelation> searchEvidenceWithPermission(@Param("query") String query,
                                                   @Param("userId") String userId,
                                                   @Param("orgTags") List<String> orgTags,
                                                   Pageable pageable);
}
