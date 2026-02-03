package com.tongji.storage.api;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.storage.OssStorageService;
import com.tongji.storage.api.dto.StoragePresignRequest;
import com.tongji.storage.api.dto.StoragePresignResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 对象存储直传接口
 */
@RestController
@RequestMapping("/api/v1/storage")
@Validated
@RequiredArgsConstructor
public class StorageController {

    /**
     * 对象存储服务
     */
    private final OssStorageService ossStorageService;

    /**
     * JWT 令牌服务
     */
    private final JwtService jwtService;

    /**
     * 知文持久层 Bean
     */
    private final KnowPostMapper knowPostMapper;

    /**
     * 获取用于直传的 PUT 类型预签名 URL。
     */
    @PostMapping("/presign")
    public StoragePresignResponse presign(@Valid @RequestBody StoragePresignRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        // 解析 JWT ，获取用户 Id
        long userId = jwtService.extractUserId(jwt);

        // 知文 Id
        long postId;
        try {
            // 前端传过来的是字符串，避免精度丢失，可能会抛出异常
            postId = Long.parseLong(request.postId());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "postId 非法");
        }

        // 权限校验：基于 postId 查找知文，并且该知文的创作者必须是当前登录用户，否则抛出异常
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != userId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        // 获取此次生成的 URL 使用场景（图片/文档类型）
        String scene = request.scene();
        // 存储路径
        String objectKey;
        // 基于上传的场景 + 请求头类型（内容类型），获取文件的扩展名
        String ext = normalizeExt(request.ext(), request.contentType(), scene);

        // 基于上传场景，构造存储路径
        if ("knowpost_content".equals(scene)) {
            objectKey = "posts/" + postId + "/content" + ext;
        } else if ("knowpost_image".equals(scene)) {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC")).format(Instant.now());
            String rand = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
            objectKey = "posts/" + postId + "/images/" + date + "/" + rand + ext;
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景");
        }

        // 此 URL 的有效时间 10 分钟
        int expiresIn = 600;

        // 调用对象存储服务生成用于直传的 PUT 类型的预签名 URL
        String putUrl = ossStorageService.generatePresignedPutUrl(objectKey, request.contentType(), expiresIn);

        // 构造响应并返回包含：OSS存储路径，直传 URL ，请求头类型 ，URL 过期时间
        // 客户端必须在上传时设置与签名一致的 Content-Type ，所以这里返回直传 URL 的同时还需要返回签名时设置的 Content-Type
        Map<String, String> headers = Map.of("Content-Type", request.contentType());
        return new StoragePresignResponse(objectKey, putUrl, headers, expiresIn);
    }

    /**
     * 基于上传的场景 + 请求头类型（内容类型），获取文件的扩展名
     * 若前端传过来扩展名，直接用即可
     */
    private String normalizeExt(String ext, String contentType, String scene) {
        // 判断前端有没有传扩展名过来，若传过来直接用这个做扩展名，否则基于上传的场景 + 内容类型获取文件扩展名
        if (ext != null && !ext.isBlank()) {
            return ext.startsWith(".") ? ext : "." + ext;
        }
        if ("knowpost_content".equals(scene)) {
            return switch (contentType) {
                case "text/markdown" -> ".md";
                case "text/html" -> ".html";
                case "text/plain" -> ".txt";
                case "application/json" -> ".json";
                default -> ".bin";
            };
        } else {
            return switch (contentType) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".img";
            };
        }
    }
}
