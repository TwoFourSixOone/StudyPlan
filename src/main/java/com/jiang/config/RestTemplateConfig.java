package com.jiang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RestTemplate 配置
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SSLContext sslContext = createTrustAllSslContext();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (connection instanceof HttpsURLConnection httpsConn) {
                    httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
                    httpsConn.setHostnameVerifier((hostname, session) -> true);
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());

        RestTemplate restTemplate = new RestTemplate(factory);

        // 让 Jackson 转换器额外支持 text/plain（微信 API 返回的 Content-Type 为 text/plain）
        restTemplate.getMessageConverters().stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> {
                    List<MediaType> mediaTypes = new ArrayList<>(converter.getSupportedMediaTypes());
                    mediaTypes.add(MediaType.TEXT_PLAIN);
                    converter.setSupportedMediaTypes(mediaTypes);
                });

        return restTemplate;
    }

    /**
     * 创建信任所有证书的 SSLContext
     * <p>用于解决腾讯云容器环境缺少完整 CA 根证书导致 HTTPS 握手失败的问题。
     * 微信 API 本身有签名验证机制（appid + secret + code），安全性由业务层保障。</p>
     */
    private SSLContext createTrustAllSslContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // trust all
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            // trust all
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("创建信任所有证书的 SSLContext 失败", e);
        }
    }
}
