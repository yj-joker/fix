package ai.weixiu.service.impl;

import ai.weixiu.config.MinioProperties;
import ai.weixiu.enumerate.BucketEnum;
import ai.weixiu.exceprion.UploadException;
import ai.weixiu.service.MioIOUpLoadService;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MioIOUpLoadServiceImpl implements MioIOUpLoadService {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    /**
     * 项目启动时自动检查并创建 Bucket
     */
    @PostConstruct
    public void init() {
        for (BucketEnum bucket : BucketEnum.values()) {
            ensureBucketExists(bucket.getName());
        }
    }


    /*
    * 返回图片的url
    * */
    @Override
    public String upload(MultipartFile file, BucketEnum bucket) {
        // 生成唯一文件名，避免覆盖
        return switch (bucket) {
            case PUBLIC -> uploadPublicFile(file,bucket.getName());
            case PRIVATE -> uploadPrivateFile(file,bucket.getName());
        };
    }



    @Override
    public InputStream download(String objectName, BucketEnum bucket) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket.getName())
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage());
            throw new RuntimeException("文件下载失败", e);
        }
    }

    @Override
    public void delete(String objectName, BucketEnum bucket) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket.getName())
                            .object(objectName)
                            .build()
            );
            log.info("文件删除成功: {}", objectName);
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage());
            throw new RuntimeException("文件删除失败", e);
        }
    }
    private String uploadPrivateFile(MultipartFile file, String name) {
        String upload = upload(file, name);
        return getPresignedUrl(upload,name,120);
    }

    private String uploadPublicFile(MultipartFile file, String name) {
        String upload = upload(file, name);
        return getFileUrl(upload, name);
    }


    /**
     * 获取文件的永久访问地址（需要 Bucket 设置为 public）
     */
    public String getFileUrl(String objectName, String bucketName) {
        return minioProperties.getEndpoint() + "/"
                + bucketName + "/" + objectName;
    }
    /**
     * 获取文件的预签名访问 URL（临时可访问链接）
     * @param objectName 对象名
     * @param expiry     过期时间（分钟）
     */
    public String getPresignedUrl(String objectName,String bucketName,int expiry) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(expiry, TimeUnit.MINUTES)
                            .build()
            );
        } catch (Exception e) {
            log.error("获取预签名 URL 失败: {}", e.getMessage());
            throw new RuntimeException("获取预签名 URL 失败", e);
        }
    }
    /*
    * 上传图片
    * */
    private @NonNull String upload(MultipartFile file, String name) {
        try {
            // 生成唯一文件名，避免覆盖
            String originalFilename = file.getOriginalFilename();
            String ext = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String objectName = UUID.randomUUID().toString().replace("-", "") + ext;

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(name)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            log.info("文件上传成功: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage());
            throw new UploadException("文件上传失败");
        }
    }
    private void ensureBucketExists(String bucketName) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("Bucket [{}] 创建成功", bucketName);
            }
        } catch (Exception e) {
            log.error("创建 Bucket [{}] 失败: {}", bucketName, e.getMessage());
            throw new RuntimeException("MinIO 初始化失败", e);
        }
    }
}
