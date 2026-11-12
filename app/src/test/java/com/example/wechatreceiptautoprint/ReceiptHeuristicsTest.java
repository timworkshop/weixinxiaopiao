package com.example.wechatreceiptautoprint;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReceiptHeuristicsTest {
    @Test public void receiptLikeTextWithAmountAndTimePasses() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额 ¥12.00 收款时间 2026-11-11 12:30 交易单号 ABC123456", 3, 6, 0.42f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.amountValue);
        assertTrue(r.timeValue);
        assertTrue(r.layoutPass);
    }

    @Test public void ordinaryQrWithNoReceiptWordsIsRejected() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify("扫码关注公众号", 2, 3, 0.35f);
        assertEquals("QR_NON_RECEIPT", r.classification);
    }

    @Test public void receiptLabelsWithoutNumericValueNeedReview() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额 收款时间", 3, 6, 0.40f);
        assertEquals("REVIEW", r.classification);
        assertFalse(r.amountValue);
        assertFalse(r.timeValue);
    }

    @Test public void numericEvidenceWithoutVerticalLayoutNeedsReview() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额¥12.00 收款时间12:30", 2, 4, 0.05f);
        assertEquals("REVIEW", r.classification);
        assertFalse(r.layoutPass);
    }

    @Test public void asciiColonAndSpacesBetweenAmountLabelAndValueAreNormalized() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额: ￥ 12.00 收款时间: 12:30", 2, 5, 0.25f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.amountValue);
        assertTrue(r.timeValue);
    }

    @Test public void genericPaymentPosterWithPriceIsNotFlaggedAsReceipt() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 ¥12.00 扫码了解详情", 2, 3, 0.30f);
        assertEquals("QR_NON_RECEIPT", r.classification);
        assertFalse(r.amountValue);
    }

    @Test public void noBoundingBoxesUsesStrictFallback() {
        ReceiptHeuristics.Result weak = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额¥12.00 收款时间12:30", 2, 4, -1f);
        ReceiptHeuristics.Result strong = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额¥12.00 收款时间12:30", 2, 5, -1f);
        assertEquals("REVIEW", weak.classification);
        assertEquals("PROBABLE_RECEIPT", strong.classification);
    }

    @Test public void fullWidthDigitsAndPunctuationAreNormalized() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额：￥１２．００ 收款时间：２０２６年１１月１２日", 3, 6, 0.35f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.amountValue);
        assertTrue(r.timeValue);
    }

    @Test public void amountAndTransactionNumberCanProvideTwoStructuredFields() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额¥12.00 交易单号 WX202611120001", 3, 6, 0.35f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.transactionValue);
    }

    @Test public void oneStructuredFieldOnlyRemainsReview() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款金额¥12.00", 3, 6, 0.35f);
        assertEquals("REVIEW", r.classification);
        assertFalse(r.transactionValue);
    }

    @Test public void timeAndTransactionNumberCanProvideTwoStructuredFields() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款时间2026-11-12 交易单号 WX202611120001", 3, 6, 0.35f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.timeValue);
        assertTrue(r.transactionValue);
    }

    @Test public void monthDayTimeFormatIsRecognized() {
        ReceiptHeuristics.Result r = ReceiptHeuristics.classify(
                "微信支付 收款成功 收款时间11-12 交易单号 WX202611120001", 3, 6, 0.35f);
        assertEquals("PROBABLE_RECEIPT", r.classification);
        assertTrue(r.timeValue);
        assertTrue(r.transactionValue);
    }
}
