package ai.weixiu.service.impl;

import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.enumerate.BucketEnum;
import ai.weixiu.exceprion.FormatErrorException;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.exceprion.NullException;
import ai.weixiu.mapper.MaintenanceManualMapper;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.service.MaintenanceManualService;
import ai.weixiu.service.MioIOUpLoadService;
import ai.weixiu.utils.BaseContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@AllArgsConstructor
@Slf4j
public class MaintenanceManualServiceImpl extends ServiceImpl<MaintenanceManualMapper, MaintenanceManual> implements MaintenanceManualService {
    private static final String MANUAL_NOT_FOUND = "Maintenance manual not found";
    private static final List<String> FILE_EXTENSIONS = List.of(".pdf", ".doc", ".docx");
    private static final List<String> FILE_CONTENT_TYPES = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
    );

    private final MioIOUpLoadService mioIOUpLoadService;

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
        log.info("Update maintenance manual success: {}", maintenanceManual.getId());
        return maintenanceManual;
    }

    @Override
    public MaintenanceManual getManualById(Long id) {
        if (id == null) {
            throw new NullException("Maintenance manual id cannot be empty");
        }
        MaintenanceManual maintenanceManual = getById(id);
        if (maintenanceManual == null) {
            throw new NotFoundException(MANUAL_NOT_FOUND);
        }
        return maintenanceManual;
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

    private void fillFileInfo(MaintenanceManual maintenanceManual, MultipartFile file) {
        maintenanceManual.setFileName(file.getOriginalFilename());
        maintenanceManual.setFileType(getFileSuffix(file));
        maintenanceManual.setFileSize(file.getSize());
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
