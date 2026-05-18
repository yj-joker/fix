package ai.weixiu.utils;

import ai.weixiu.config.BaiDuConfigurationProperties;
import ai.weixiu.pojo.dto.BaiDuAsrDTO;
import ai.weixiu.pojo.dto.BaiDuTokenDTO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class VoiceToTextUtils {

    private final WebClient tokenClient;  // aip.baidubce.com — 用于获取 token
    private final WebClient asrClient;   // vop.baidu.com — 用于语音识别
    private final BaiDuConfigurationProperties properties;

    private static final long FFMPEG_TIMEOUT_SECONDS = 30;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private String cachedToken;
    private long expireAtMillis;

    // FFmpeg 音频转码
    public Path convertToPcm(Path inputFile) {
        try {
            Path outputFile = Files.createTempFile("baidu-asr-", ".pcm");
            int rate = properties.getRate() == null ? 16000 : properties.getRate();

            ProcessBuilder builder = new ProcessBuilder(
                    "ffmpeg",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-nostdin",
                    "-y",
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-vn",
                    "-map", "0:a:0",
                    "-acodec", "pcm_s16le",
                    "-ac", "1",
                    "-ar", String.valueOf(rate),
                    "-f", "s16le",
                    outputFile.toAbsolutePath().toString()
            );

            /*
             * FFmpeg 会把日志写到 stderr。若只 waitFor() 而不消费输出，日志较多时管道会被写满，
             * Java 线程一直等待，表现为“转码失败/卡住”。这里合并输出并在 waitFor 前读完。
             */
            builder.redirectErrorStream(true);

            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                deleteQuietly(outputFile);
                throw new IllegalStateException("FFmpeg 音频转码超时（" + FFMPEG_TIMEOUT_SECONDS + "秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                deleteQuietly(outputFile);
                throw new IllegalStateException("FFmpeg 音频转码失败: " + output.trim());
            }

            if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
                deleteQuietly(outputFile);
                throw new IllegalStateException("FFmpeg 音频转码失败: 输出 PCM 文件为空");
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
            int rate = properties.getRate() == null ? 16000 : properties.getRate();

            BaiDuAsrDTO response = asrClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/server_api")
                            .queryParam("dev_pid", properties.getDevPid())
                            .queryParam("cuid", properties.getCuid())
                            .queryParam("token", token)
                            .build())
                    .contentType(MediaType.parseMediaType("audio/pcm;rate=" + rate))
                    .bodyValue(pcmBytes)
                    .retrieve()
                    .bodyToMono(BaiDuAsrDTO.class)
                    .block(HTTP_TIMEOUT);

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

        int maxMb = properties.getMaxUploadMb() == null ? 10 : properties.getMaxUploadMb();
        long maxBytes = maxMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("音频文件不能超过 " + maxMb + "MB");
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
        this.tokenClient = builder.clone().baseUrl("https://aip.baidubce.com").build();
        this.asrClient = builder.clone().baseUrl("https://vop.baidu.com").build();
        this.properties = properties;
    }


    public synchronized String getAccessToken() {
        if (cachedToken != null && System.currentTimeMillis() < expireAtMillis) {
            return cachedToken;
        }

        BaiDuTokenDTO response = tokenClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/oauth/2.0/token")
                        .queryParam("grant_type", "client_credentials")
                        .queryParam("client_id", properties.getApiKey())
                        .queryParam("client_secret", properties.getSecretKey())
                        .build())
                .retrieve()
                .bodyToMono(BaiDuTokenDTO.class)
                .block(HTTP_TIMEOUT);

        if (response == null || response.getAccessToken() == null) {
            throw new IllegalStateException("获取百度 access_token 失败");
        }

        this.cachedToken = response.getAccessToken();

        long expiresIn = response.getExpiresIn() == null ? 2592000 : response.getExpiresIn();
        this.expireAtMillis = System.currentTimeMillis() + (expiresIn - 3600) * 1000;

        return cachedToken;
    }
}
