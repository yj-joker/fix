package ai.weixiu.service.impl;

import ai.weixiu.common.RedisKey;
import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.enumerate.BucketEnum;
import ai.weixiu.exceprion.FormatErrorException;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.mapper.MaintenanceManualMapper;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.pojo.vo.MaintenanceManualVO;
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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@AllArgsConstructor
@Slf4j
/**
 * 维修手册 CRUD 与详情缓存服务。
 *
 * <p>详情缓存只保存数据库中的稳定字段。手册文档存放在 MinIO 私有桶中，
 * 预签名访问地址会过期，因此在组装详情响应时实时生成，不写入详情缓存。</p>
 */
public class MaintenanceManualServiceImpl extends ServiceImpl<MaintenanceManualMapper, MaintenanceManual> implements MaintenanceManualService {
    private static final String MANUAL_NOT_FOUND = "Maintenance manual not found";
    private static final String EMPTY_CACHE_VALUE = "__NULL__";
    private static final int PRIVATE_FILE_URL_EXPIRY_MINUTES = 120;
    private static final List<String> FILE_EXTENSIONS = List.of(".pdf", ".doc", ".docx");
    private static final List<String> FILE_CONTENT_TYPES = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
    );

    private final MioIOUpLoadService mioIOUpLoadService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    @Override
    @Transactional
    public MaintenanceManual add(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file) {
        validateFile(file);

        MaintenanceManual maintenanceManual = new MaintenanceManual();
        BeanUtils.copyProperties(maintenanceManualDTO, maintenanceManual, "id");
        maintenanceManual.setId(IdWorker.getId());
        fillFileInfo(maintenanceManual, file);
        maintenanceManual.setStatus(1);
        maintenanceManual.setCreatedById(BaseContext.getCurrentId());
        LocalDateTime now = LocalDateTime.now();
        maintenanceManual.setCreatedAt(now);
        maintenanceManual.setUpdatedAt(now);
        save(maintenanceManual);
        evictManualCache(maintenanceManual.getId());
        log.info("Add maintenance manual success: {}", maintenanceManual.getId());
        return maintenanceManual;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        MaintenanceManual maintenanceManual = getManualById(id);
        if (StringUtils.hasText(maintenanceManual.getMinioObjectName())) {
            mioIOUpLoadService.delete(maintenanceManual.getMinioObjectName(), BucketEnum.PRIVATE);
        }
        removeById(id);
        evictManualCache(id);
        log.info("Delete maintenance manual success: {}", id);
    }

    @Override
    @Transactional
    public MaintenanceManual update(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file) {
        if (maintenanceManualDTO.getId() == null) {
            throw new NullException("Maintenance manual id cannot be empty");
        }

        MaintenanceManual maintenanceManual = getManualById(maintenanceManualDTO.getId());
        BeanUtils.copyProperties(maintenanceManualDTO, maintenanceManual, "id");
        if (file != null && !file.isEmpty()) {
            validateFile(file);
            String oldObjectName = maintenanceManual.getMinioObjectName();
            fillFileInfo(maintenanceManual, file);
            if (StringUtils.hasText(oldObjectName)) {
                mioIOUpLoadService.delete(oldObjectName, BucketEnum.PRIVATE);
            }
        }
        maintenanceManual.setUpdatedAt(LocalDateTime.now());
        updateById(maintenanceManual);
        evictManualCache(maintenanceManual.getId());
        log.info("Update maintenance manual success: {}", maintenanceManual.getId());
        return maintenanceManual;
    }

    @Override
    public MaintenanceManual getManualById(Long id) {
        if (id == null) {
            throw new NullException("Maintenance manual id cannot be empty");
        }
        String cacheKey = RedisKey.MANUAL_DETAIL + id;

        // 缓存命中时直接返回。若命中空值标记，则直接抛出不存在异常，
        // 避免不存在的手册 id 持续穿透到数据库。
        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        MaintenanceManual cachedManual = parseManualCache(cachedValue);
        if (cachedManual != null) {
            return cachedManual;
        }

        // 每本手册使用独立的分布式锁重建缓存。进入锁后再次查询缓存，
        // 防止等待锁的请求在前一个线程已完成缓存重建后仍重复回源数据库。
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
        MaintenanceManual maintenanceManual = getManualById(id);
        MaintenanceManualVO manualVO = new MaintenanceManualVO();
        BeanUtils.copyProperties(maintenanceManual, manualVO);

        // 文档存放在 MinIO 私有桶中，数据库只保存稳定的对象名。
        // 每次查询详情都基于对象名生成新的预签名 URL，供前端临时访问文档。
        if (StringUtils.hasText(maintenanceManual.getMinioObjectName())) {
            manualVO.setFileUrl(mioIOUpLoadService.getPresignedUrl(
                    maintenanceManual.getMinioObjectName(),
                    BucketEnum.PRIVATE,
                    PRIVATE_FILE_URL_EXPIRY_MINUTES
            ));
        }
        return manualVO;
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

    private MaintenanceManual parseManualCache(Object cachedValue) {
        if (cachedValue == null) {
            return null;
        }
        if (EMPTY_CACHE_VALUE.equals(cachedValue)) {
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        if (cachedValue instanceof MaintenanceManual maintenanceManual) {
            return maintenanceManual;
        }
        evictManualCacheValue(cachedValue);
        return null;
    }

    private MaintenanceManual loadManualToCache(Long id, String cacheKey) {
        MaintenanceManual maintenanceManual = getById(id);
        if (maintenanceManual == null) {
            // 对不存在的手册写入短期空值缓存，拦住重复非法 id 查询；
            // TTL 保持较短，避免后续真实数据写入后长时间不可见。
            redisTemplate.opsForValue().set(cacheKey, EMPTY_CACHE_VALUE, emptyCacheTtl());
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        // 正常详情缓存增加随机 TTL，避免大量热门手册在同一时刻过期，
        // 从而把瞬时流量同时压回 MySQL。
        redisTemplate.opsForValue().set(cacheKey, maintenanceManual, manualCacheTtl());
        return maintenanceManual;
    }

    private void evictManualCache(Long id) {
        redisTemplate.delete(RedisKey.MANUAL_DETAIL + id);
    }

    private void evictManualCacheValue(Object cachedValue) {
        log.warn("Ignore unsupported maintenance manual cache value: {}", cachedValue.getClass().getName());
    }

    private Duration manualCacheTtl() {
        // 防缓存雪崩：基础 30 分钟，再附加 0-10 分钟随机过期时间。
        return Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(11));
    }

    private Duration emptyCacheTtl() {
        // 防缓存穿透：空值缓存保留更短时间。
        return Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(121));
    }

    private void fillFileInfo(MaintenanceManual maintenanceManual, MultipartFile file) {
        maintenanceManual.setFileName(file.getOriginalFilename());
        maintenanceManual.setFileType(getFileSuffix(file));
        maintenanceManual.setFileSize(file.getSize());
        // 上传到私有桶后保存对象名，后续用它生成预签名 URL 或删除文件；
        // 这里保存的不是浏览器可直接访问的永久地址。
        maintenanceManual.setMinioObjectName(mioIOUpLoadService.getObjectName(file, BucketEnum.PRIVATE.getName()));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new NullException("Maintenance manual file cannot be empty");
        }
        String fileSuffix = getFileSuffix(file).toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();
        boolean validExtension = FILE_EXTENSIONS.contains(fileSuffix);
        boolean validContentType = contentType != null && FILE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
        if (!validExtension || !validContentType) {
            throw new FormatErrorException("Only pdf, doc and docx files are supported");
        }
    }

    private String getFileSuffix(MultipartFile file) {
        String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
        if (!originalFilename.contains(".")) {
            throw new FormatErrorException("File extension cannot be empty");
        }
        return originalFilename.substring(originalFilename.lastIndexOf("."));
    }
}
