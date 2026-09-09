package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private GraphExtractionService graphExtractionService;

    @Autowired
    private GraphDocumentStateService graphDocumentStateService;

    @Value("${rag.graph.extraction-enabled:false}")
    private boolean graphExtractionEnabled;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            chunks.get(i).getPageNumber(),
                            chunks.get(i).getAnchorText(),
                            vectors.get(i),
                            embeddingResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            rebuildGraphIfEnabled(fileMd5, userId, orgTag, isPublic);

            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }

    private void rebuildGraphIfEnabled(String fileMd5, String userId, String orgTag, boolean isPublic) {
        if (!graphExtractionEnabled) {
            return;
        }

        try {
            if (!graphDocumentStateService.isAutoBuildAllowed(fileMd5, userId, orgTag, isPublic)) {
                logger.info("Graph RAG 抽取跳过，文件未启用建图: fileMd5={}", fileMd5);
                return;
            }
            graphDocumentStateService.markBuilding(fileMd5, userId, orgTag, isPublic);
            GraphExtractionService.GraphExtractionResult result = graphExtractionService.rebuildForFile(fileMd5, userId, orgTag, isPublic);
            graphDocumentStateService.markCompleted(fileMd5, result);
            logger.info(
                    "Graph RAG 抽取完成，fileMd5: {}, chunks: {}, entities: {}, mentions: {}, relations: {}, buildMs: {}",
                    fileMd5,
                    result.chunkCount(),
                    result.entityCount(),
                    result.mentionCount(),
                    result.relationCount(),
                    result.buildMs()
            );
        } catch (Exception e) {
            markGraphFailedSafely(fileMd5, e);
            logger.warn("Graph RAG 抽取失败，保持向量化结果返回，fileMd5: {}, error: {}", fileMd5, e.getMessage(), e);
        }
    }

    private void markGraphFailedSafely(String fileMd5, Exception extractionException) {
        try {
            graphDocumentStateService.markFailed(fileMd5, extractionException.getMessage());
        } catch (Exception stateException) {
            logger.warn(
                    "Graph RAG 失败状态标记失败，保持向量化结果返回，fileMd5: {}, error: {}",
                    fileMd5,
                    stateException.getMessage(),
                    stateException
            );
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText()
                ))
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
