package com.cloud.alibaba.ai.example.skills.skillsagentexample.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ZipUploadLimitsTest {

    @Test
    @DisplayName("解压后总字节未超限,放行")
    void totalBytes_underLimit_passes() {
        assertDoesNotThrow(() -> ZipUploadLimits.checkTotalBytes(ZipUploadLimits.MAX_TOTAL_UNZIPPED_BYTES - 1));
    }

    @Test
    @DisplayName("解压后总字节超限,返回 413")
    void totalBytes_overLimit_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> ZipUploadLimits.checkTotalBytes(ZipUploadLimits.MAX_TOTAL_UNZIPPED_BYTES + 1));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getStatusCode().value() == 413);
    }

    @Test
    @DisplayName("条目数未超限,放行")
    void entryCount_underLimit_passes() {
        assertDoesNotThrow(() -> ZipUploadLimits.checkEntryCount(ZipUploadLimits.MAX_ENTRY_COUNT));
    }

    @Test
    @DisplayName("条目数超限,返回 413")
    void entryCount_overLimit_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> ZipUploadLimits.checkEntryCount(ZipUploadLimits.MAX_ENTRY_COUNT + 1));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getStatusCode().value() == 413);
    }

    @Test
    @DisplayName("压缩比正常,放行")
    void ratio_normal_passes() {
        assertDoesNotThrow(() -> ZipUploadLimits.checkEntryRatio(1024, 1024 * 50));
    }

    @Test
    @DisplayName("压缩比异常(1KB -> 1GB),拒绝(炸弹嫌疑)")
    void ratio_zipBomb_rejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> ZipUploadLimits.checkEntryRatio(1024, 1024L * 1024 * 1024));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getStatusCode().value() == 413);
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("Suspicious compression ratio"));
    }

    @Test
    @DisplayName("压缩比为 0 不会触发炸弹检测(避免 /0)")
    void ratio_zero_safe() {
        assertDoesNotThrow(() -> ZipUploadLimits.checkEntryRatio(0, 100));
    }
}