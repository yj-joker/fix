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
    /** 对外统一使用的手册不存在提示。 */
    private static final String MANUAL_NOT_FOUND = "Maintenance manual not found";

    /** Redis 中的空值占位符，用于缓存不存在的手册 id，降低恶意或错误查询对数据库的压力。 */
    private static final String EMPTY_CACHE_VALUE = "__NULL__";

    /** 私有桶文档预签名地址的有效时长，单位为分钟。 */
    private static final int PRIVATE_FILE_URL_EXPIRY_MINUTES = 120;

    /** 手册上传允许的文件后缀。后缀校验用于拦住明显不符合业务约束的文件。 */
    private static final List<String> FILE_EXTENSIONS = List.of(".pdf", ".doc", ".docx");

    /** 手册上传允许的 MIME 类型。部分浏览器上传 Word 文件时会给出 octet-stream。 */
    private static final List<String> FILE_CONTENT_TYPES = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
    );

    /** 负责私有桶文件上传、删除和预签名地址生成。 */
    private final MioIOUpLoadService mioIOUpLoadService;

    /** 手册详情缓存使用对象序列化，因此这里保留对象类型 RedisTemplate。 */
    private final RedisTemplate<String, Object> redisTemplate;

    /** 缓存击穿场景下使用的分布式锁客户端。 */
    private final RedissonClient redissonClient;

    @Override
    @Transactional
    /**
     * 新增手册。
     *
     * <p>新增时先校验文档类型，再由服务端生成雪花 id，随后把文件上传到 MinIO 私有桶，
     * 把对象名、文件元数据和手册基础信息一起落库。详情缓存按“写后删除”处理，
     * 这样即使同 id 的历史空值缓存曾经存在，也不会继续影响新数据读取。</p>
     */
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
    /**
     * 删除手册。
     *
     * <p>先读取手册拿到私有桶对象名，再删除 MinIO 文件和数据库记录，最后删除详情缓存。
     * 删除缓存是为了让详情页和排行榜展示不再继续读取到旧的手册信息。</p>
     */
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
    /**
     * 更新手册基础信息和可选文档文件。
     *
     * <p>当请求没有携带新文件时，只更新标题、描述等业务字段；当携带了新文件时，
     * 会先上传新文件并替换实体中的文件元数据，再删除旧 MinIO 对象。
     * 更新完成后清理详情缓存，让后续请求重新回源并缓存最新版本。</p>
     */
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
    /**
     * 查询手册基础信息并维护详情缓存。
     *
     * <p>读取顺序是“查缓存 -> 未命中后竞争分布式锁 -> 锁内二次查缓存 -> 回源数据库并回填缓存”。
     * 未查到数据库记录时写入短 TTL 空值缓存，用于防穿透；正常缓存 TTL 带随机值，用于降低雪崩风险；
     * 按手册 id 加锁，用于降低热点详情缓存失效时的击穿风险。</p>
     */
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
    /**
     * 查询详情页响应。
     *
     * <p>详情页在基础手册字段之外需要文档地址。由于手册文件位于 MinIO 私有桶，
     * 预签名 URL 会过期，不能和基础详情一起长期缓存，所以这里每次根据稳定对象名即时生成。</p>
     */
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
    /**
     * 按条件分页查询手册列表。
     *
     * <p>列表查询直接走数据库分页，不复用详情缓存；名称为空时不加模糊条件，
     * 状态为空时不加状态条件，排序方向由查询参数控制。</p>
     */
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

    /**
     * 解释 Redis 中的详情缓存值。
     *
     * <p>{@code null} 表示缓存未命中；空值占位符表示之前已经确认该 id 不存在；
     * 实体对象表示正常命中。若遇到旧版本或错误类型缓存值，只记录日志并按未命中处理，
     * 避免类型问题直接阻断正常回源。</p>
     */
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

    /**
     * 从数据库加载手册并回填缓存。
     *
     * <p>正常记录写入随机 TTL 详情缓存；不存在的记录写入短 TTL 空值缓存后抛异常。
     * 该方法只负责回源和回填，锁控制由 {@link #getManualById(Long)} 负责。</p>
     */
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

    /** 删除指定手册的详情缓存。 */
    private void evictManualCache(Long id) {
        redisTemplate.delete(RedisKey.MANUAL_DETAIL + id);
    }

    /**
     * 处理不受支持的缓存值。
     *
     * <p>当前实现只做告警并交给后续回源覆盖，避免在读取路径主动删除未知值造成额外误伤。</p>
     */
    private void evictManualCacheValue(Object cachedValue) {
        log.warn("Ignore unsupported maintenance manual cache value: {}", cachedValue.getClass().getName());
    }

    /** 生成正常详情缓存 TTL，基础时长加随机值用于错开集中失效时间。 */
    private Duration manualCacheTtl() {
        // 防缓存雪崩：基础 30 分钟，再附加 0-10 分钟随机过期时间。
        return Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(11));
    }

    /** 生成空值缓存 TTL，时长较短以兼顾防穿透和新数据可见性。 */
    private Duration emptyCacheTtl() {
        // 防缓存穿透：空值缓存保留更短时间。
        return Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(121));
    }

    /**
     * 填充文档元数据并上传源文件。
     *
     * <p>数据库保存原始文件名、后缀、大小和 MinIO 对象名。
     * 对象名是后续删除文件和生成预签名地址的稳定锚点，不是前端长期访问地址。</p>
     */
    private void fillFileInfo(MaintenanceManual maintenanceManual, MultipartFile file) {
        maintenanceManual.setFileName(file.getOriginalFilename());
        maintenanceManual.setFileType(getFileSuffix(file));
        maintenanceManual.setFileSize(file.getSize());
        // 上传到私有桶后保存对象名，后续用它生成预签名 URL 或删除文件；
        // 这里保存的不是浏览器可直接访问的永久地址。
        maintenanceManual.setMinioObjectName(mioIOUpLoadService.getObjectName(file, BucketEnum.PRIVATE.getName()));
    }

    /**
     * 校验手册上传文件。
     *
     * <p>同时检查后缀和 MIME 类型，是为了降低只改文件名或只伪造 Content-Type 带来的误判。
     * 这里放行 {@code application/octet-stream} 是为了兼容部分客户端上传 Word 文件时的通用二进制类型。</p>
     */
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

    /** 获取原始文件后缀，缺少后缀时直接按格式错误处理。 */
    private String getFileSuffix(MultipartFile file) {
        String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
        if (!originalFilename.contains(".")) {
            throw new FormatErrorException("File extension cannot be empty");
        }
        return originalFilename.substring(originalFilename.lastIndexOf("."));
    }
}
