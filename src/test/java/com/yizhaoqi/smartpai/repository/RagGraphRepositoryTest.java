package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.RagEntity;
import com.yizhaoqi.smartpai.model.RagEntityMention;
import com.yizhaoqi.smartpai.model.RagCrossDocumentRelation;
import com.yizhaoqi.smartpai.model.RagRelation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rag_graph_repository;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RagGraphRepositoryTest {

    @Autowired
    private RagEntityRepository entityRepository;

    @Autowired
    private RagEntityMentionRepository mentionRepository;

    @Autowired
    private RagRelationRepository relationRepository;

    @Autowired
    private RagCrossDocumentRelationRepository crossDocumentRelationRepository;

    @Test
    void deleteOrphanEntitiesKeepsEntitiesReferencedByMentionOrRelation() {
        RagEntity orphan = entityRepository.save(entity("Orphan", "orphan", "general"));
        RagEntity mentioned = entityRepository.save(entity("Mentioned", "mentioned", "general"));
        RagEntity source = entityRepository.save(entity("Source", "source", "general"));
        RagEntity target = entityRepository.save(entity("Target", "target", "general"));
        RagEntity crossSource = entityRepository.save(entity("CrossSource", "crosssource", "general"));
        RagEntity crossTarget = entityRepository.save(entity("CrossTarget", "crosstarget", "general"));

        RagEntityMention mention = new RagEntityMention();
        mention.setEntity(mentioned);
        mention.setFileMd5("md5");
        mention.setChunkId(1);
        mention.setUserId("1");
        mentionRepository.save(mention);

        RagRelation relation = new RagRelation();
        relation.setSourceEntity(source);
        relation.setTargetEntity(target);
        relation.setRelationType("co_occurs");
        relation.setFileMd5("md5");
        relation.setChunkId(1);
        relation.setUserId("1");
        relationRepository.save(relation);

        RagCrossDocumentRelation crossRelation = crossRelation(crossSource, crossTarget, "file-a", "file-b");
        crossDocumentRelationRepository.save(crossRelation);

        int deleted = entityRepository.deleteOrphanEntities();

        assertEquals(1, deleted);
        assertFalse(entityRepository.findById(orphan.getId()).isPresent());
        assertTrue(entityRepository.findById(mentioned.getId()).isPresent());
        assertTrue(entityRepository.findById(source.getId()).isPresent());
        assertTrue(entityRepository.findById(target.getId()).isPresent());
        assertTrue(entityRepository.findById(crossSource.getId()).isPresent());
        assertTrue(entityRepository.findById(crossTarget.getId()).isPresent());
    }

    @Test
    void updatePermissionByFileMd5UpdatesMentionsAndRelations() {
        RagEntity source = entityRepository.save(entity("Source", "source", "general"));
        RagEntity target = entityRepository.save(entity("Target", "target", "general"));

        RagEntityMention mention = new RagEntityMention();
        mention.setEntity(source);
        mention.setFileMd5("md5");
        mention.setChunkId(1);
        mention.setUserId("1");
        mention.setOrgTag("OLD_TEAM");
        mention.setPublic(false);
        mentionRepository.save(mention);

        RagRelation relation = new RagRelation();
        relation.setSourceEntity(source);
        relation.setTargetEntity(target);
        relation.setRelationType("co_occurs");
        relation.setFileMd5("md5");
        relation.setChunkId(1);
        relation.setUserId("1");
        relation.setOrgTag("OLD_TEAM");
        relation.setPublic(false);
        relationRepository.save(relation);

        assertEquals(1, mentionRepository.updatePermissionByFileMd5("md5", "2", "NEW_TEAM", true));
        assertEquals(1, relationRepository.updatePermissionByFileMd5("md5", "2", "NEW_TEAM", true));

        RagEntityMention updatedMention = mentionRepository.findById(mention.getId()).orElseThrow();
        RagRelation updatedRelation = relationRepository.findById(relation.getId()).orElseThrow();
        assertEquals("2", updatedMention.getUserId());
        assertEquals("NEW_TEAM", updatedMention.getOrgTag());
        assertTrue(updatedMention.isPublic());
        assertEquals("2", updatedRelation.getUserId());
        assertEquals("NEW_TEAM", updatedRelation.getOrgTag());
        assertTrue(updatedRelation.isPublic());
    }

    @Test
    void crossDocumentRelationRequiresPermissionOnBothFilesAndDeletesByEitherFile() {
        RagEntity entity = entityRepository.save(entity("奖学金", "奖学金", "award"));
        RagCrossDocumentRelation relation = crossRelation(entity, entity, "file-a", "file-b");
        relation.setSourcePublic(true);
        relation.setTargetPublic(false);
        relation.setTargetUserId("9");
        relation.setTargetOrgTag("SECRET");
        crossDocumentRelationRepository.save(relation);

        assertEquals(0, crossDocumentRelationRepository.findByFileMd5WithPermission("file-a", "2", List.of("TEAM_A")).size());

        relation.setTargetOrgTag("TEAM_A");
        crossDocumentRelationRepository.save(relation);
        assertEquals(1, crossDocumentRelationRepository.findByFileMd5WithPermission("file-a", "2", List.of("TEAM_A")).size());

        assertEquals(1, crossDocumentRelationRepository.deleteByAnyFileMd5("file-b"));
        assertEquals(0, crossDocumentRelationRepository.count());
    }

    private RagEntity entity(String name, String normalizedName, String type) {
        RagEntity entity = new RagEntity();
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setType(type);
        return entity;
    }

    private RagCrossDocumentRelation crossRelation(RagEntity source, RagEntity target, String sourceFileMd5, String targetFileMd5) {
        RagCrossDocumentRelation relation = new RagCrossDocumentRelation();
        relation.setSourceEntity(source);
        relation.setTargetEntity(target);
        relation.setRelationType(RagCrossDocumentRelation.TYPE_SAME_ENTITY);
        relation.setSourceFileMd5(sourceFileMd5);
        relation.setSourceChunkId(1);
        relation.setSourceEvidenceText("source evidence");
        relation.setSourceUserId("1");
        relation.setSourceOrgTag("TEAM_A");
        relation.setSourcePublic(false);
        relation.setTargetFileMd5(targetFileMd5);
        relation.setTargetChunkId(2);
        relation.setTargetEvidenceText("target evidence");
        relation.setTargetUserId("2");
        relation.setTargetOrgTag("TEAM_A");
        relation.setTargetPublic(false);
        return relation;
    }
}
