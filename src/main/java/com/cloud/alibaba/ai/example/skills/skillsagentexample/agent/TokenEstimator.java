package com.cloud.alibaba.ai.example.skills.skillsagentexample.agent;

/**
 * Token 估算工具。
 * <p>
 * 由于项目未引入专用 tokenizer，采用启发式估算：
 * <ul>
 *   <li>中文（CJK 统一表意文字）：约 1 token / 1.5 字符</li>
 *   <li>英文/ASCII 字符：约 1 token / 4 字符</li>
 *   <li>混合文本：分别统计后求和</li>
 * </ul>
 * 这种估算比简单的 {@code 字符数/2} 更准确，尤其对中英混合场景。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算文本的 token 数。
     *
     * @param text 输入文本
     * @return 估算的 token 数（向下取整，至少 0）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int asciiCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkCount++;
            } else if (c < 128) {
                asciiCount++;
            }
            // 其他字符（如标点、emoji）忽略不计入，避免高估
        }
        // CJK: 1 token ≈ 1.5 chars; ASCII: 1 token ≈ 4 chars
        double tokens = (cjkCount / 1.5) + (asciiCount / 4.0);
        return (int) Math.floor(tokens);
    }

    /**
     * 判断字符是否为 CJK 统一表意文字（中文、日文、韩文）。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意文字
            || (c >= 0x3400 && c <= 0x4DBF)   // CJK 扩展 A
            || (c >= 0x3040 && c <= 0x30FF)   // 日文假名
            || (c >= 0xAC00 && c <= 0xD7AF);  // 韩文音节
    }
}
