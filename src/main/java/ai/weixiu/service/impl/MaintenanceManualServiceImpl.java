package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.KnowledgeDocument;
import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.enumerate.BucketEnum;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.mapper.MaintenanceManualMapper;
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

    private static final String MANUAL_NOT_FOUND = "Maintenance manual not found";
    private static final String EMPTY_CACHE_VALUE = "__NULL__";
    private static final int PRIVATE_FILE_URL_EXPIRY_MINUTES = 120;

    private final MioIOUpLoadService mioIOUpLoadService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final KnowledgeDocumentService knowledgeDocumentService;

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

        // 2. 上传第一版文档
        knowledgeDocumentService.uploadNewVersion(manual.getId(), file);

        log.info("新增手册成功: {}, 已发起 v1 解析", manual.getId());
        return manual;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        MaintenanceManual manual = getManualById(id);

        // 删除所有版本的文档记录和 MinIO 文件
        List<KnowledgeDocument> versions = knowledgeDocumentService.listVersions(id);
        for (KnowledgeDocument doc : versions) {
            if (StringUtils.hasText(doc.getMinioObjectName())) {
                try {
                    mioIOUpLoadService.delete(doc.getMinioObjectName(), BucketEnum.PRIVATE);
                } catch (Exception e) {
                    log.warn("删除 MinIO 文件失败: {}", doc.getMinioObjectName(), e);
                }
            }
            knowledgeDocumentService.removeById(doc.getId());
        }

        // 兼容：删除旧字段指向的 MinIO 文件
        if (StringUtils.hasText(manual.getMinioObjectName())) {
            try {
                mioIOUpLoadService.delete(manual.getMinioObjectName(), BucketEnum.PRIVATE);
            } catch (Exception e) {
                log.warn("删除旧 MinIO 文件失败: {}", manual.getMinioObjectName(), e);
            }
        }

        removeById(id);
        evictManualCache(id);
        log.info("删除手册成功: {}, 共删除 {} 个版本", id, versions.size());
    }

    @Override
    @Transactional
    public MaintenanceManual update(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file) {
        if (maintenanceManualDTO.getId() == null) {
            throw new NullException("Maintenance manual id cannot be empty");
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

        // 有新文件时上传新版本
        if (file != null && !file.isEmpty()) {
            knowledgeDocumentService.uploadNewVersion(manual.getId(), file);
            manual.setStatus(2); // 处理中
            updateById(manual);
            log.info("更新手册并上传新版本: {}", manual.getId());
        } else {
            log.info("更新手册元数据: {}", manual.getId());
        }

        return manual;
    }

    @Override
    public MaintenanceManual getManualById(Long id) {
        if (id == null) {
            throw new NullException("Maintenance manual id cannot be empty");
        }
        String cacheKey = RedisKey.MANUAL_DETAIL + id;

        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        MaintenanceManual cachedManual = parseManualCache(cachedValue);
        if (cachedManual != null) {
            return cachedManual;
        }

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
            throw new RuntimeException("Get maintenance manual cache lock interrupted", e);
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

    private MaintenanceManual parseManualCache(Object cachedValue) {
        if (cachedValue == null) return null;
        if (EMPTY_CACHE_VALUE.equals(cachedValue)) {
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        if (cachedValue instanceof MaintenanceManual m) return m;
        log.warn("忽略不受支持的手册缓存值: {}", cachedValue.getClass().getName());
        return null;
    }

    private MaintenanceManual loadManualToCache(Long id, String cacheKey) {
        MaintenanceManual manual = getById(id);
        if (manual == null) {
            redisTemplate.opsForValue().set(cacheKey, EMPTY_CACHE_VALUE, emptyCacheTtl());
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        redisTemplate.opsForValue().set(cacheKey, manual, manualCacheTtl());
        return manual;
    }

    private void evictManualCache(Long id) {
        redisTemplate.delete(RedisKey.MANUAL_DETAIL + id);
    }

    private Duration manualCacheTtl() {
        return Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(11));
    }

    private Duration emptyCacheTtl() {
        return Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(121));
    }
}
