package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.KnowledgeDocument;
import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.enumerate.BucketEnum;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.mapper.MaintenanceManualMapper;
import ai.weixiu.mq.KnowledgeImportProducer;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.pojo.vo.MaintenanceManualVO;
import ai.weixiu.service.KnowledgeDocumentService;
import ai.weixiu.service.MaintenanceManualService;
import ai.weixiu.service.MioIOUpLoadService;
import ai.weixiu.utils.BaseContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
@Slf4j
public class MaintenanceManualServiceImpl
        extends ServiceImpl<MaintenanceManualMapper, MaintenanceManual>
        implements MaintenanceManualService {

    /** 手册不存在提示。 */
    private static final String MANUAL_NOT_FOUND = "维修手册不存在";

    /** Redis 中的空值占位符，用于缓存不存在的手册 id，降低穿透压力。 */
    private static final String EMPTY_CACHE_VALUE = "__NULL__";

    /** 私有桶文档预签名地址有效时长（分钟）。 */
    private static final int PRIVATE_FILE_URL_EXPIRY_MINUTES = 120;

    private final MioIOUpLoadService mioIOUpLoadService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeImportProducer knowledgeImportProducer;

    @Override
    @Transactional
    public MaintenanceManual add(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file) {
        // 1. 保存手册元数据（不含文件信息）
        MaintenanceManual manual = new MaintenanceManual();
        BeanUtils.copyProperties(maintenanceManualDTO, manual, "id");
        manual.setId(IdWorker.getId());
        manual.setStatus(2); // 处理中
        manual.setCreatedById(BaseContext.getCurrentId());
        LocalDateTime now = LocalDateTime.now();
        manual.setCreatedAt(now);
        manual.setUpdatedAt(now);
        save(manual);
        evictManualCache(manual.getId());

        // 2. 上传第一版文档（文件信息将在 onParseSuccess 回调中统一回写到 manual）
        knowledgeDocumentService.uploadNewVersion(manual.getId(), file);

        log.info("新增手册成功: {}, 已发起 v1 解析", manual.getId());
        return manual;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        MaintenanceManual manual = getManualById(id);

        // 收集需要在事务提交后清理的资源信息
        List<KnowledgeDocument> versions = knowledgeDocumentService.listVersions(id);
        List<String> documentIdsToDelete = new java.util.ArrayList<>();
        List<String> minioObjectsToDelete = new java.util.ArrayList<>();

        for (KnowledgeDocument doc : versions) {
            if (StringUtils.hasText(doc.getDocumentId())) {
                documentIdsToDelete.add(doc.getDocumentId());
            }
            if (StringUtils.hasText(doc.getMinioObjectName())) {
                minioObjectsToDelete.add(doc.getMinioObjectName());
            }
            // 事务内只做 DB 删除
            knowledgeDocumentService.removeById(doc.getId());
        }

        // 兼容旧数据：旧字段指向的 MinIO 文件也需要清理
        if (StringUtils.hasText(manual.getMinioObjectName())) {
            minioObjectsToDelete.add(manual.getMinioObjectName());
        }

        removeById(id);
        evictManualCache(id);
        log.info("删除手册成功: {}, 共删除 {} 个版本", id, versions.size());

        // 事务提交后再执行不可逆的副作用（MQ 消息 + MinIO 文件删除）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String documentId : documentIdsToDelete) {
                    try {
                        knowledgeImportProducer.sendDeleteTask(documentId);
                    } catch (Exception e) {
                        log.warn("发送向量删除消息失败: documentId={}", documentId, e);
                    }
                }
                for (String objectName : minioObjectsToDelete) {
                    try {
                        mioIOUpLoadService.delete(objectName, BucketEnum.PRIVATE);
                    } catch (Exception e) {
                        log.warn("删除 MinIO 文件失败: {}", objectName, e);
                    }
                }
            }
        });
    }

    @Override
    @Transactional
    public MaintenanceManual update(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file) {
        if (maintenanceManualDTO.getId() == null) {
            throw new NullException("手册 ID 不能为空");
        }

        MaintenanceManual manual = getManualById(maintenanceManualDTO.getId());

        // 更新元数据字段
        if (StringUtils.hasText(maintenanceManualDTO.getManualName())) {
            manual.setManualName(maintenanceManualDTO.getManualName());
        }
        if (maintenanceManualDTO.getManualImage() != null) {
            manual.setManualImage(maintenanceManualDTO.getManualImage());
        }
        if (maintenanceManualDTO.getManualDesc() != null) {
            manual.setManualDesc(maintenanceManualDTO.getManualDesc());
        }

        manual.setUpdatedAt(LocalDateTime.now());
        updateById(manual);
        evictManualCache(manual.getId());

        // 有新文件时上传新版本（uploadNewVersion 内部已设 status=2 并写库+清缓存）
        if (file != null && !file.isEmpty()) {
            knowledgeDocumentService.uploadNewVersion(manual.getId(), file);
            evictManualCache(manual.getId());
            log.info("更新手册并上传新版本: {}", manual.getId());
        } else {
            log.info("更新手册元数据: {}", manual.getId());
        }

        return manual;
    }

    @Override
    public MaintenanceManual getManualById(Long id) {
        if (id == null) {
            throw new NullException("手册 ID 不能为空");
        }
        String cacheKey = RedisKey.MANUAL_DETAIL + id;

        // 缓存命中时直接返回；命中空值标记则抛出不存在异常
        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        MaintenanceManual cachedManual = parseManualCache(cachedValue);
        if (cachedManual != null) {
            return cachedManual;
        }

        // 分布式锁保护缓存重建，防止击穿
        RLock lock = redissonClient.getLock(RedisKey.MANUAL_DETAIL_LOCK + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, 10, TimeUnit.SECONDS);
            if (locked) {
                cachedValue = redisTemplate.opsForValue().get(cacheKey);
                cachedManual = parseManualCache(cachedValue);
                if (cachedManual != null) {
                    return cachedManual;
                }
                return loadManualToCache(id, cacheKey);
            }
            cachedValue = redisTemplate.opsForValue().get(cacheKey);
            cachedManual = parseManualCache(cachedValue);
            if (cachedManual != null) {
                return cachedManual;
            }
            return loadManualToCache(id, cacheKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取手册缓存锁被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public MaintenanceManualVO getManualDetailById(Long id) {
        MaintenanceManual manual = getManualById(id);
        MaintenanceManualVO vo = new MaintenanceManualVO();
        BeanUtils.copyProperties(manual, vo);

        // 从 active KnowledgeDocument 获取文件信息
        if (manual.getActiveDocumentId() != null) {
            KnowledgeDocument activeDoc = knowledgeDocumentService.getById(manual.getActiveDocumentId());
            if (activeDoc != null) {
                vo.setFileName(activeDoc.getFileName());
                vo.setFileType(activeDoc.getFileType());
                vo.setFileSize(activeDoc.getFileSize());
                vo.setActiveVersion(activeDoc.getVersion());
                vo.setTextCount(activeDoc.getTextCount());
                vo.setImageCount(activeDoc.getImageCount());
                vo.setTableCount(activeDoc.getTableCount());

                if (StringUtils.hasText(activeDoc.getMinioObjectName())) {
                    vo.setFileUrl(mioIOUpLoadService.getPresignedUrl(
                            activeDoc.getMinioObjectName(),
                            BucketEnum.PRIVATE,
                            PRIVATE_FILE_URL_EXPIRY_MINUTES
                    ));
                }
            }
        } else {
            // 兼容旧数据：active_document_id 为空时走原来的逻辑
            if (StringUtils.hasText(manual.getMinioObjectName())) {
                vo.setFileUrl(mioIOUpLoadService.getPresignedUrl(
                        manual.getMinioObjectName(),
                        BucketEnum.PRIVATE,
                        PRIVATE_FILE_URL_EXPIRY_MINUTES
                ));
            }
        }

        // 最新版本的解析状态
        KnowledgeDocument latestDoc = knowledgeDocumentService.getLatestVersion(id);
        if (latestDoc != null) {
            vo.setParseStatus(latestDoc.getStatus());
            vo.setParseErrorMessage(latestDoc.getErrorMessage());
        }

        // 版本总数
        List<KnowledgeDocument> versions = knowledgeDocumentService.listVersions(id);
        vo.setTotalVersions(versions.size());

        return vo;
    }

    @Override
    public PageResult<MaintenanceManual> getManualList(MaintenanceManualQuery query) {
        Integer pageNum = query.getPage() == null ? 1 : query.getPage();
        Integer pageSize = query.getSize() == null ? 10 : query.getSize();
        Page<MaintenanceManual> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<MaintenanceManual> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(query.getManualName()), MaintenanceManual::getManualName, query.getManualName())
                .eq(query.getStatus() != null, MaintenanceManual::getStatus, query.getStatus())
                .orderBy(true, !Objects.equals(query.getIsAsc(), 1), MaintenanceManual::getCreatedAt);
        Page<MaintenanceManual> result = page(page, wrapper);
        return new PageResult<>(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    // ===== 缓存逻辑 =====

    /** 解析 Redis 中的详情缓存值。 */
    private MaintenanceManual parseManualCache(Object cachedValue) {
        if (cachedValue == null) return null;
        if (EMPTY_CACHE_VALUE.equals(cachedValue)) {
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        if (cachedValue instanceof MaintenanceManual m) return m;
        log.warn("忽略不受支持的手册缓存值: {}", cachedValue.getClass().getName());
        return null;
    }

    /** 从数据库加载手册并回填缓存。 */
    private MaintenanceManual loadManualToCache(Long id, String cacheKey) {
        MaintenanceManual manual = getById(id);
        if (manual == null) {
            // 不存在的手册写入短期空值缓存，防穿透
            redisTemplate.opsForValue().set(cacheKey, EMPTY_CACHE_VALUE, emptyCacheTtl());
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        // 正常缓存加随机 TTL 防雪崩
        redisTemplate.opsForValue().set(cacheKey, manual, manualCacheTtl());
        return manual;
    }

    /** 删除指定手册的详情缓存。 */
    private void evictManualCache(Long id) {
        redisTemplate.delete(RedisKey.MANUAL_DETAIL + id);
    }

    /** 正常详情缓存 TTL：30-40 分钟随机，防雪崩。 */
    private Duration manualCacheTtl() {
        return Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(11));
    }

    /** 空值缓存 TTL：60-180 秒随机，兼顾防穿透和新数据可见性。 */
    private Duration emptyCacheTtl() {
        return Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(121));
    }
}
