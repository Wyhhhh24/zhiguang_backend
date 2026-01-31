package com.tongji.auth.config;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM 密钥读取工具，读取本地的私钥、公钥文件。
 * <p>
 * 支持从 `Resource` 读取 PKCS#8 私钥与 X.509 公钥，去除头尾与空白后得到的是 Base64 进行编码的干净字符串
 * 然后 Base64 解码得到所需要的字节数组
 * 生成 `RSAPrivateKey` 与 `RSAPublicKey`。用于 JWT 的 RS256 编解码配置。
 */
public final class PemUtils {

    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END = "-----END PUBLIC KEY-----";

    /**
     * 工具类私有构造函数，防止实例化。
     */
    private PemUtils() {
    }

    /**
     * 从 PEM 资源读取 RSA 私钥（PKCS#8 格式）。
     *
     * @param resource Spring {@link org.springframework.core.io.Resource}，指向私钥 PEM 文件。
     * @return 解析得到的 {@link RSAPrivateKey}。
     * @throws IllegalStateException 当读取或解析失败时抛出。
     */
    public static RSAPrivateKey readPrivateKey(Resource resource) {
        try {
            String pem = readResource(resource);
            // 字符串标准化处理、格式化提取，得到 Base64 编码的干净字符串
            String keyData = pem.replace(PRIVATE_BEGIN, "")
                    .replace(PRIVATE_END, "")
                    .replaceAll("\\s", ""); // 删除字符串中所有空白字符
            // 将 Base64 编码的字符串解码为原始的字节数组
            byte[] keyBytes = Base64.getDecoder().decode(keyData);

            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            // 从 Key 工厂中获取 RSA Key 工厂实例
            KeyFactory kf = KeyFactory.getInstance("RSA");
            // 最后得到 RSAPrivateKey 对象并返回
            return (RSAPrivateKey) kf.generatePrivate(spec);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to read RSA private key", ex);
        }
    }

    /**
     * 从 PEM 资源读取 RSA 公钥（X.509 格式）。
     *
     * @param resource Spring {@link org.springframework.core.io.Resource}，指向公钥 PEM 文件。
     * @return 解析得到的 {@link RSAPublicKey}。
     * @throws IllegalStateException 当读取或解析失败时抛出。
     */
    public static RSAPublicKey readPublicKey(Resource resource) {
        try {
            String pem = readResource(resource);
            // 字符串标准化处理、格式化提取，得到 Base64 编码的干净字符串
            String keyData = pem.replace(PUBLIC_BEGIN, "")
                    .replace(PUBLIC_END, "")
                    .replaceAll("\\s", ""); // 删除字符串中所有空白字符
            // 将 Base64 编码的字符串解码为原始的字节数组
            byte[] keyBytes = Base64.getDecoder().decode(keyData);

            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            // 从 Key 工厂中获取 RSA Key 工厂实例
            KeyFactory kf = KeyFactory.getInstance("RSA");
            // 最后得到 RSAPublic 对象并返回
            return (RSAPublicKey) kf.generatePublic(spec);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to read RSA public key", ex);
        }
    }

    /**
     * 读取给定资源的文本内容。
     *
     * @param resource 待读取的资源。
     * @return 使用 UTF-8 解码的文本内容。
     * @throws IOException 发生 I/O 错误时抛出。
     */
    private static String readResource(Resource resource) throws IOException {
        // 自动关闭 InputStream
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

