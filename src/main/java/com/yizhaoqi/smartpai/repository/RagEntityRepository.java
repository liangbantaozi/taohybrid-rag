package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface RagEntityRepository extends JpaRepository<RagEntity, Long> {

    Optional<RagEntity> findByNormalizedNameAndType(String normalizedName, String type);

    @Query("""
            select distinct e
            from RagEntityMention m
            join m.entity e
            where (m.userId = :userId or m.isPublic = true or m.orgTag in :orgTags)
              and (
                  lower(e.name) like lower(concat('%', :keyword, '%'))
                  or lower(e.normalizedName) like lower(concat('%', :normalizedKeyword, '%'))
              )
            order by e.name asc
            """)
    List<RagEntity> searchAccessibleEntities(@Param("keyword") String keyword,
                                             @Param("normalizedKeyword") String normalizedKeyword,
                                             @Param("userId") String userId,
                                             @Param("orgTags") List<String> orgTags,
                                             Pageable pageable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from RagEntity e
            where not exists (
                select m.id from RagEntityMention m where m.entity = e
            )
              and not exists (
                select r.id from RagRelation r where r.sourceEntity = e
            )
              and not exists (
                select r.id from RagRelation r where r.targetEntity = e
            )
              and not exists (
                select r.id from RagCrossDocumentRelation r where r.sourceEntity = e
            )
              and not exists (
                select r.id from RagCrossDocumentRelation r where r.targetEntity = e
            )
            """)
    int deleteOrphanEntities();
}
