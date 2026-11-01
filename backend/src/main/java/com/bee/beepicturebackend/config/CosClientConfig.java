package com.bee.beepicturebackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    /**
     * 域名
     */
    private String host;
    /**
     * 密钥id
     */
    private String secretId;
    /**
     * 密钥key
     */
    private String secretKey;
    /**
     * 区域
     */
    private String region;
    /**
     * 存储桶
     */
    private String bucket;

    @Bean
    public COSClient cosClient(){
        if (secretId == null || secretId.isBlank() || secretKey == null || secretKey.isBlank()
                || bucket == null || bucket.isBlank() || host == null || host.isBlank()) {
            throw new IllegalStateException("Configure COS_HOST, COS_BUCKET, COS_SECRET_ID and COS_SECRET_KEY before starting the backend");
        }
        //初始化用户身份信息(secretId, secretKey)
        BasicCOSCredentials cred = new BasicCOSCredentials(secretId,secretKey);
        // 2 设置 bucket 的地域
        // clientConfig 中包含了设置 region, https(默认 http), 超时, 代理等 set 方法, 使用可参见源码或者常见问题 Java SDK 部分
        //COS_REGION 参数：配置成存储桶 bucket 的实际地域，例如 ap-beijing，更多 COS 地域的简称请参见 https://cloud.tencent.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 3 生成 cos 客户端
        return new COSClient(cred, clientConfig);
    }
}
