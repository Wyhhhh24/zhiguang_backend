package com.tongji.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.tongji.storage.config.OssProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.net.URL;
import java.util.Date;

/**
 * OSS 对象存储服务
 */
@Service
@RequiredArgsConstructor
public class OssStorageService {

    /**
     * OSS 属性配置类
     */
    private final OssProperties props;

    /**
     * 上传头像
     */
    public String uploadAvatar(long userId, MultipartFile file) {
        // 确保 OSS 属性已配置，若未配置则抛异常
        ensureConfigured();
        // 获得文件原始名称
        String original = file.getOriginalFilename();

        // 获取文件扩展名，包含 . ，例如： .png
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }

        // 拼接存储路径
        // Instant.now().toEpochMilli() 获取当前的毫秒级时间戳
        // 形如：avatars/1024-1707000000000.jpg
        String objectKey = props.getFolder() + "/" + userId + "-" + Instant.now().toEpochMilli() + ext;

        // 初始化 OSS 客户端实例
        // 文档中提示：当 OSSClient 实例不再使用时，调用shutdown方法以释放资源。
        OSS client = new OSSClientBuilder()
                .build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());

        try {
            // 使用客户端实例，上传文件
            PutObjectRequest request = new PutObjectRequest(props.getBucket(), objectKey, file.getInputStream());
            client.putObject(request);
        } catch (IOException e) {
            // 文件上传易抛出异常，捕捉异常
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件读取失败");
        } finally {
            // 最后释放资源
            client.shutdown();
        }

        // 返回可直接访问的 URL
        return publicUrl(objectKey);
    }


    /**
     * 生成用于直传的 PUT 类型的预签名 URL。
     * 客户端必须在上传时设置与签名一致的 Content-Type。
     * 具体看 OSS 的官方文档，有详细的案例
     *
     * @param objectKey 目标对象键
     * @param contentType 上传内容类型（如 text/markdown, image/png）
     * @param expiresInSeconds 有效期秒数（建议 300-900）
     * @return 可直接用于 PUT 上传的预签名 URL
     */
    public String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds) {
        // 确保配置文件中，相关配置已正常配置，若未配置则抛出异常
        ensureConfigured();

        // 初始化 OSS 客户端实例
        OSS client = new OSSClientBuilder()
                .build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());

        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(props.getBucket(), objectKey, HttpMethod.PUT);

            // System.currentTimeMillis() → 返回当前时间距离 1970-01-01 00:00:00 UTC 的毫秒数（一个 long 类型的值）
            // expiresInSeconds * 1000L → 把秒数转换成毫秒数（因为时间戳是用毫秒计量的，所以要 ×1000）
            // 两者相加 → 得到未来某个时刻的时间戳（毫秒）
            // new Date(那个毫秒值) → 创建一个代表“那个未来时刻”的 Date 对象
            Date expiration = new Date(System.currentTimeMillis() + expiresInSeconds * 1000L);
            // 设置过期时间（文档中说，单位是毫秒）
            request.setExpiration(expiration);

            // 客户端必须在上传时设置与签名一致的 Content-Type
            if (contentType != null && !contentType.isBlank()) {
                request.setContentType(contentType);
            }

            // 生成用于直传的 PUT 类型的预签名 URL
            URL url = client.generatePresignedUrl(request);
            // 返回
            return url.toString();
        } finally {
            // 最后释放资源
            client.shutdown();
        }
    }


    /**
     * 拼接可访问的 URL
     */
    private String publicUrl(String objectKey) {
        // 如果自定义了域名， URL 就通过域名进行拼接
        if (props.getPublicDomain() != null && !props.getPublicDomain().isBlank()) {
            return props.getPublicDomain().replaceAll("/$", "") + "/" + objectKey;
        }
        // 直接拼接可访问的 URL
        return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + objectKey;
    }


    /**
     * 确保配置文件中，相关配置已正常配置
     */
    private void ensureConfigured() {
        if (props.getEndpoint() == null || props.getAccessKeyId() == null || props.getAccessKeySecret() == null || props.getBucket() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }
}
