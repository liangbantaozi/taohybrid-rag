package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagGraphDocumentState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Repository
public interface RagGraphDocumentStateRepository extends JpaRepository<RagGraphDocumentState, Long> {

    Optional<RagGraphDocumentState> findByFileMd5(String fileMd5);

    Page<RagGraphDocumentState> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    @Query("""
            select s.fileMd5
            from RagGraphDocumentState s
            where s.graphEnabled = true
              and s.graphStatus = :graphStatus
              and s.fileMd5 <> :excludedFileMd5
            order by s.updatedAt desc
            """)
    List<String> findEnabledFileMd5sByGraphStatusExcluding(@Param("graphStatus") String graphStatus,
                                                           @Param("excludedFileMd5") String excludedFileMd5,
                                                           Pageable pageable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RagGraphDocumentState s where s.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);
}
