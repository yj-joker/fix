package ai.weixiu.utils;

import ai.weixiu.entity.BaiDuConfigurationProperties;
import ai.weixiu.pojo.dto.BaiDuAsrDTO;
import ai.weixiu.pojo.dto.BaiDuTokenDTO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class VoiceToTextUtils {

    private final WebClient webClient;
    private final BaiDuConfigurationProperties properties;

    private String cachedToken;
    private long expireAtMillis;

    // FFmpeg 音频转码
    public Path convertToPcm(Path inputFile) {
        try {
            Path outputFile = Files.createTempFile("baidu-asr-", ".pcm");

            ProcessBuilder builder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-vn",
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "s16le",
                    outputFile.toAbsolutePath().toString()
            );

            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorOutput = new String(process.getErrorStream().readAllBytes());
                throw new IllegalStateException("FFmpeg 音频转码失败: " + errorOutput.trim());
            }

            return outputFile;
        } catch (IOException e) {
            throw new IllegalStateException("执行 FFmpeg 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg 转码被中断", e);
        }
    }

    //百度识别服务
    public String transcribe(MultipartFile file) {
        validateFile(file);

        Path inputFile = null;
        Path pcmFile = null;

        try {
            inputFile = Files.createTempFile("upload-asr-", getSuffix(file.getOriginalFilename()));
            Files.copy(file.getInputStream(), inputFile, StandardCopyOption.REPLACE_EXISTING);

            pcmFile = convertToPcm(inputFile);

            byte[] pcmBytes = Files.readAllBytes(pcmFile);
            String token = getAccessToken();

            BaiDuAsrDTO response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/server_api")
                            .queryParam("dev_pid", properties.getDevPid())
                            .queryParam("cuid", properties.getCuid())
                            .queryParam("token", token)
                            .build())
                    .contentType(MediaType.parseMediaType("audio/pcm;rate=" + properties.getRate()))
                    .bodyValue(pcmBytes)
                    .retrieve()
                    .bodyToMono(BaiDuAsrDTO.class)
                    .block();

            if (response == null) {
                throw new IllegalStateException("百度语音识别无响应");
            }

            if (response.getErrNo() == null || response.getErrNo() != 0) {
                throw new IllegalStateException("百度语音识别失败：" + response.getErrMsg());
            }

            if (response.getResult() == null || response.getResult().isEmpty()) {
                return "";
            }

            return String.join("", response.getResult());

        } catch (IOException e) {
            throw new IllegalStateException("读取音频文件失败", e);
        } finally {
            deleteQuietly(inputFile);
            deleteQuietly(pcmFile);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("音频文件不能为空");
        }

        long maxBytes = properties.getMaxUploadMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("音频文件不能超过 " + properties.getMaxUploadMb() + "MB");
        }

        String suffix = getSuffix(file.getOriginalFilename()).toLowerCase();
        if (!suffix.matches("\\.(mp3|wav|webm|m4a|mp4|ogg|amr|pcm)")) {
            throw new IllegalArgumentException("不支持的音频格式：" + suffix);
        }
    }

    private String getSuffix(String filename) {
        if (filename == null || !filename.contains(".")) {
            return ".audio";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public VoiceToTextUtils(WebClient.Builder builder,
                            BaiDuConfigurationProperties properties) {
        this.webClient = builder.baseUrl("https://aip.baidubce.com").build();
        this.properties = properties;
    }


    public synchronized String getAccessToken() {
        if (cachedToken != null && System.currentTimeMillis() < expireAtMillis) {
            return cachedToken;
        }

        BaiDuTokenDTO response = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/oauth/2.0/token")
                        .queryParam("grant_type", "client_credentials")
                        .queryParam("client_id", properties.getApiKey())
                        .queryParam("client_secret", properties.getSecretKey())
                        .build())
                .retrieve()
                .bodyToMono(BaiDuTokenDTO.class)
                .block();

        if (response == null || response.getAccessToken() == null) {
            throw new IllegalStateException("获取百度 access_token 失败");
        }

        this.cachedToken = response.getAccessToken();

        long expiresIn = response.getExpiresIn() == null ? 2592000 : response.getExpiresIn();
        this.expireAtMillis = System.currentTimeMillis() + (expiresIn - 3600) * 1000;

        return cachedToken;
    }
}
