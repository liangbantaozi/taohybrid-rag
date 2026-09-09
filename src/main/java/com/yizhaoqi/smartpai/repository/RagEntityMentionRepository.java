package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEntityMention;
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
public interface RagEntityMentionRepository extends JpaRepository<RagEntityMention, Long> {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RagEntityMention m where m.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RagEntityMention m
            set m.userId = :userId,
                m.orgTag = :orgTag,
                m.isPublic = :isPublic
            where m.fileMd5 = :fileMd5
            """)
    int updatePermissionByFileMd5(@Param("fileMd5") String fileMd5,
                                  @Param("userId") String userId,
                                  @Param("orgTag") String orgTag,
                                  @Param("isPublic") boolean isPublic);

    @Query("""
            select m
            from RagEntityMention m
            join fetch m.entity
            where m.fileMd5 = :fileMd5
              and (m.userId = :userId or m.isPublic = true or m.orgTag in :orgTags)
            order by m.chunkId asc, m.id asc
            """)
    List<RagEntityMention> findByFileMd5WithPermission(@Param("fileMd5") String fileMd5,
                                                       @Param("userId") String userId,
                                                       @Param("orgTags") List<String> orgTags);

    @Query("""
            select m
            from RagEntityMention m
            join fetch m.entity
            where m.entity.id = :entityId
              and (m.userId = :userId or m.isPublic = true or m.orgTag in :orgTags)
            order by m.fileMd5 asc, m.chunkId asc, m.id asc
            """)
    List<RagEntityMention> findByEntityIdWithPermission(@Param("entityId") Long entityId,
                                                        @Param("userId") String userId,
                                                        @Param("orgTags") List<String> orgTags);

    @Query("""
            select m
            from RagEntityMention m
            join fetch m.entity
            where m.entity.id in :entityIds
              and (m.userId = :userId or m.isPublic = true or m.orgTag in :orgTags)
            order by m.fileMd5 asc, m.chunkId asc, m.id asc
            """)
    List<RagEntityMention> findByEntityIdsWithPermission(@Param("entityIds") Collection<Long> entityIds,
                                                         @Param("userId") String userId,
                                                         @Param("orgTags") List<String> orgTags,
                                                         Pageable pageable);

    @Query("""
            select m
            from RagEntityMention m
            join fetch m.entity
            where m.entity.id in :entityIds
              and m.fileMd5 in :fileMd5s
            order by m.fileMd5 asc, m.chunkId asc, m.id asc
            """)
    List<RagEntityMention> findByEntityIdsAndFileMd5In(@Param("entityIds") Collection<Long> entityIds,
                                                       @Param("fileMd5s") Collection<String> fileMd5s,
                                                       Pageable pageable);

    @Query("""
            select m
            from RagEntityMention m
            join fetch m.entity
            where (m.userId = :userId or m.isPublic = true or m.orgTag in :orgTags)
              and (
                  lower(m.evidenceText) like lower(concat('%', :query, '%'))
                  or lower(m.anchorText) like lower(concat('%', :query, '%'))
              )
            order by m.id desc
            """)
    List<RagEntityMention> searchEvidenceWithPermission(@Param("query") String query,
                                                        @Param("userId") String userId,
                                                        @Param("orgTags") List<String> orgTags,
                                                        Pageable pageable);
}
