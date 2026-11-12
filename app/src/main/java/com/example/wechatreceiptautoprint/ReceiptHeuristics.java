package com.example.wechatreceiptautoprint;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Conservative QR + OCR receipt heuristic. QR payloads and raw OCR text are never stored. */
public final class ReceiptHeuristics {
    private static final String[] KEYWORDS = {
            "收款金额", "收款时间", "微信支付", "收款成功", "交易单号",
            "收款方", "付款方", "收款小票", "微信收款", "支付成功",
            "订单号", "商户单号", "付款金额", "到账金额", "交易时间", "支付时间"
    };
    private static final Pattern AMOUNT_VALUE = Pattern.compile(
            "(?:收款金额|付款金额|到账金额)[:：]?[¥￥]?\\d+(?:\\.\\d{1,2})?");
    private static final Pattern TIME_VALUE = Pattern.compile(
            "(?:收款时间|交易时间|支付时间)[:：]?(?:20\\d{2}(?:[-/.年]?\\d{1,2}(?:[-/.月]?\\d{1,2}日?)?)?|\\d{1,2}[-/.月]\\d{1,2}日?|\\d{1,2}:\\d{2}|\\d{4})");
    private static final Pattern TRANSACTION_VALUE = Pattern.compile(
            "(?:交易单号|商户单号|订单号)[:：]?[A-Z0-9-]{5,}");

    private ReceiptHeuristics() { }

    public static Result classify(String rawText, int blockCount, int lineCount, float verticalSpreadRatio) {
        String normalized = normalize(rawText);
        int matches = 0;
        StringBuilder matched = new StringBuilder();
        for (String keyword : KEYWORDS) {
            if (normalized.contains(keyword)) {
                matches++;
                if (matched.length() > 0) matched.append("、");
                matched.append(keyword);
            }
        }

        boolean amountValue = AMOUNT_VALUE.matcher(normalized).find();
        boolean timeValue = TIME_VALUE.matcher(normalized).find();
        boolean transactionValue = TRANSACTION_VALUE.matcher(normalized).find();
        // Real OCR geometry is preferred. If unavailable, use a stricter text-density fallback.
        boolean geometryPass = verticalSpreadRatio >= 0.14f && lineCount >= 4;
        boolean fallbackPass = verticalSpreadRatio < 0f && blockCount >= 2 && lineCount >= 5;
        boolean layoutPass = geometryPass || fallbackPass;

        // A probable receipt needs at least two independent structured fields:
        // amount + time, amount + transaction number, or time + transaction number.
        int structuredEvidence = (amountValue ? 1 : 0) + (timeValue ? 1 : 0) + (transactionValue ? 1 : 0);
        boolean explicitReceiptCue = normalized.contains("收款金额") || normalized.contains("收款时间") ||
                normalized.contains("收款成功") || normalized.contains("交易单号") ||
                normalized.contains("收款小票") || normalized.contains("微信收款") ||
                normalized.contains("商户单号") || normalized.contains("订单号");
        String classification = matches >= 2 && structuredEvidence >= 2 && layoutPass
                ? "PROBABLE_RECEIPT"
                : (matches >= 2 || explicitReceiptCue ? "REVIEW" : "QR_NON_RECEIPT");

        return new Result(matches, matched.toString(), blockCount, lineCount,
                verticalSpreadRatio, amountValue, timeValue, transactionValue, layoutPass, classification);
    }

    private static String normalize(String rawText) {
        if (rawText == null) return "";
        // NFKC converts full-width digits/letters/punctuation into forms handled by the rules.
        String value = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
        return value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("，", "").replace(",", "")
                .replace("。", "").replace("、", "")
                .replace("；", "").replace(";", "").replace(" ", "");
    }

    public static final class Result {
        public final int keywordCount;
        public final String matchedKeywords;
        public final int blockCount;
        public final int lineCount;
        public final float verticalSpreadRatio;
        public final boolean amountValue;
        public final boolean timeValue;
        public final boolean transactionValue;
        public final boolean layoutPass;
        public final String classification;

        Result(int keywordCount, String matchedKeywords, int blockCount, int lineCount,
               float verticalSpreadRatio, boolean amountValue, boolean timeValue,
               boolean transactionValue, boolean layoutPass, String classification) {
            this.keywordCount = keywordCount;
            this.matchedKeywords = matchedKeywords;
            this.blockCount = blockCount;
            this.lineCount = lineCount;
            this.verticalSpreadRatio = verticalSpreadRatio;
            this.amountValue = amountValue;
            this.timeValue = timeValue;
            this.transactionValue = transactionValue;
            this.layoutPass = layoutPass;
            this.classification = classification;
        }
    }
}
