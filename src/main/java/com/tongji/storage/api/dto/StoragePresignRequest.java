package com.tongji.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 预签名直传请求。
 */
public record StoragePresignRequest(
        @NotBlank String scene, // 上传的场景，knowpost_content 知文内容（文档类型）| knowpost_image 知文图片（图片类型）
        @NotBlank String postId, // 知文 Id ，字符串避免前端精度丢失
        @NotBlank String contentType, // 如 text/markdown, image/png ，上传的文件类型，请求头类型
        String ext
) {}