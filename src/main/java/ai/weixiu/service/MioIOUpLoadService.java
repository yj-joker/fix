package ai.weixiu.service;

import ai.weixiu.enumerate.BucketEnum;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface MioIOUpLoadService {
    String upload(MultipartFile file, BucketEnum bucket);

    InputStream download(String objectName, BucketEnum bucket);

    void delete(String objectName, BucketEnum bucket);

}
