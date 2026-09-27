package com.cloud.alibaba.ai.example.skills.skillsagentexample.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

/**
 * OpenAI / ModelScope 客户端超时与重试配置。
 *
 * <p>提供 {@link WebClient.Builder} Bean,带:</p>
 * <ul>
 *   <li>连接超时: {@code openai.connect-timeout-ms}</li>
 *   <li>读超时: {@code openai.read-timeout-ms}</li>
 *   <li>指数退避重试: 429 / 5xx 触发,最大 4 次</li>
 * </ul>
 *
 * <p>Spring AI 1.1 默认通过 {@code RestClientBuilderConfigurer} 复用容器里的
 * {@code RestClient.Builder};本项目额外暴露 {@link WebClient.Builder} 以备
 * {@code ChatClient} 自定义 HTTP 客户端时直接注入。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "openai")
public class OpenAiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClientConfig.class);

    @Value("${openai.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${openai.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Value("${openai.retry.max-attempts:4}")
    private int maxAttempts;

    @Value("${openai.retry.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    @Value("${openai.retry.multiplier:2.0}")
    private double multiplier;

    @Value("${openai.retry.max-backoff-ms:8000}")
    private long maxBackoffMs;

    private final AtomicReference<HttpClient> sharedHttpClient = new AtomicReference<>();

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }

    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long v) { this.initialBackoffMs = v; }

    public double getMultiplier() { return multiplier; }
    public void setMultiplier(double v) { this.multiplier = v; }

    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long v) { this.maxBackoffMs = v; }

    /**
     * 给业务代码使用的 WebClient.Builder:已配置超时与 429/5xx 重试。
     */
    @Bean
    public WebClient.Builder openAiWebClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)));
        sharedHttpClient.set(httpClient);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    /**
     * 通用指数退避 Retry 信号,可被任意 Mono/Flux 链上 .retryWhen(retry) 复用。
     * 仅对 429 / 5xx 重试,避免对业务错误(4xx)无谓重试。
     */
    public Retry retrySpec() {
        return Retry.backoff(Math.max(1, maxAttempts - 1),
                        Duration.ofMillis(initialBackoffMs))
                .maxBackoff(Duration.ofMillis(maxBackoffMs))
                .multiplier(multiplier)
                .transientErrors(true)
                .filter(t -> {
                    if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                        int code = wre.getStatusCode().value();
                        return code == 429 || (code >= 500 && code < 600);
                    }
                    return t instanceof java.net.ConnectException
                            || t instanceof java.util.concurrent.TimeoutException;
                })
                .doBeforeRetry(s -> log.warn("OpenAI retry #{} due to {}",
                        s.totalRetries() + 1,
                        s.failure().toString()));
    }

    @PreDestroy
    public void shutdown() {
        HttpClient hc = sharedHttpClient.getAndSet(null);
        // Reactor Netty 的 HttpClient 由连接器管理,无需显式 dispose;
        // 保留 hook 以便未来扩展(连接池 metrics flush 等)。
        if (hc != null) {
            log.debug("OpenAI HttpClient released");
        }
    }
}