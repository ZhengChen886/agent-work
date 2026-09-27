package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * ZIP 上传大小/炸弹防护阈值(共享常量,SkillService 与 AgentService 共用)。
 *
 * <p>默认值来自 application.yml 的同名 key,通过
 * {@code @Value} 注入到这两个常量。直接修改字段值即可调整行为。</p>
 */
public final class ZipUploadLimits {

    private ZipUploadLimits() {}

    /** 单个 ZIP 解压后总字节上限。 */
    public static long MAX_TOTAL_UNZIPPED_BYTES = 50L * 1024 * 1024; // 50MB

    /** ZIP 中条目数上限。 */
    public static int MAX_ENTRY_COUNT = 200;

    /**
     * 压缩比上限:解压后大小 / 压缩大小。
     * 超过该比例视为 ZIP 炸弹嫌疑,拒绝解压。
     * 默认 100 意味着 1KB 压缩包最多膨胀到 100KB。
     */
    public static double MAX_ENTRY_RATIO = 100.0;

    /** 单个文件上传大小(由 multipart resolver 提前校验)。 */
    public static long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    /**
     * 在 ZIP 解压过程中累计字节数;若超出 {@link #MAX_TOTAL_UNZIPPED_BYTES},
     * 抛出 {@link ResponseStatusException}(413 Payload Too Large)。
     */
    public static void checkTotalBytes(long total) {
        if (total > MAX_TOTAL_UNZIPPED_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Unzipped total " + total + " exceeds limit " + MAX_TOTAL_UNZIPPED_BYTES);
        }
    }

    public static void checkEntryCount(int count) {
        if (count > MAX_ENTRY_COUNT) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Entry count " + count + " exceeds limit " + MAX_ENTRY_COUNT);
        }
    }

    public static void checkEntryRatio(long compressed, long uncompressed) {
        if (compressed <= 0) return;
        double ratio = (double) uncompressed / (double) compressed;
        if (ratio > MAX_ENTRY_RATIO) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Suspicious compression ratio " + String.format("%.1f", ratio)
                            + " > max " + MAX_ENTRY_RATIO);
        }
    }
}