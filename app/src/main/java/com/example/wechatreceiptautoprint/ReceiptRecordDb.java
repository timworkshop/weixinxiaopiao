package com.example.wechatreceiptautoprint;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local, privacy-conscious recognition history. QR payloads and raw OCR text are never stored. */
public final class ReceiptRecordDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "receipt_recognition.db";
    private static final int DB_VERSION = 4;
    private static final String TABLE = "recognition_records";

    public ReceiptRecordDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "uri TEXT PRIMARY KEY," +
                "display_name TEXT NOT NULL," +
                "width INTEGER NOT NULL DEFAULT 0," +
                "height INTEGER NOT NULL DEFAULT 0," +
                "qr_count INTEGER NOT NULL DEFAULT 0," +
                "keyword_count INTEGER NOT NULL DEFAULT 0," +
                "matched_keywords TEXT NOT NULL DEFAULT ''," +
                "text_blocks INTEGER NOT NULL DEFAULT 0," +
                "text_lines INTEGER NOT NULL DEFAULT 0," +
                "amount_value INTEGER NOT NULL DEFAULT 0," +
                "time_value INTEGER NOT NULL DEFAULT 0," +
                "transaction_value INTEGER NOT NULL DEFAULT 0," +
                "layout_pass INTEGER NOT NULL DEFAULT 0," +
                "vertical_spread REAL NOT NULL DEFAULT 0," +
                "classification TEXT NOT NULL," +
                "review_state TEXT NOT NULL DEFAULT 'NONE'," +
                "processed_at INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX idx_recognition_processed_at ON " + TABLE + "(processed_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN amount_value INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN time_value INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN layout_pass INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN vertical_spread REAL NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN transaction_value INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN review_state TEXT NOT NULL DEFAULT 'NONE'");
            db.execSQL("UPDATE " + TABLE + " SET review_state='WAITING' WHERE classification IN ('PROBABLE_RECEIPT','REVIEW')");
        }
    }

    public synchronized boolean hasRecord(String uri) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM " + TABLE + " WHERE uri=? LIMIT 1", new String[]{uri})) {
            return c.moveToFirst();
        }
    }

    public synchronized void insertBaseline(String uri, String name) {
        ContentValues v = new ContentValues();
        v.put("uri", uri);
        v.put("display_name", name == null ? "unknown" : name);
        v.put("classification", "BASELINE");
        v.put("processed_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void saveResult(String uri, String name, int width, int height,
                                         int qrCount, int keywordCount, String matchedKeywords,
                                         int blocks, int lines, boolean amountValue, boolean timeValue, boolean transactionValue,
                                         boolean layoutPass, float verticalSpread, String classification) {
        ContentValues v = new ContentValues();
        v.put("uri", uri);
        v.put("display_name", name == null ? "unknown" : name);
        v.put("width", Math.max(0, width));
        v.put("height", Math.max(0, height));
        v.put("qr_count", qrCount);
        v.put("keyword_count", keywordCount);
        v.put("matched_keywords", matchedKeywords == null ? "" : matchedKeywords);
        v.put("text_blocks", Math.max(0, blocks));
        v.put("text_lines", Math.max(0, lines));
        v.put("amount_value", amountValue ? 1 : 0);
        v.put("time_value", timeValue ? 1 : 0);
        v.put("transaction_value", transactionValue ? 1 : 0);
        v.put("layout_pass", layoutPass ? 1 : 0);
        v.put("vertical_spread", verticalSpread);
        v.put("classification", classification);
        v.put("review_state", ("PROBABLE_RECEIPT".equals(classification) || "REVIEW".equals(classification)) ? "WAITING" : "NONE");
        v.put("processed_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<String> getRecentSummaries(int limit) {
        List<String> rows = new ArrayList<>();
        String sql = "SELECT display_name, classification, processed_at, qr_count, keyword_count, matched_keywords, " +
                "amount_value, time_value, transaction_value, layout_pass, vertical_spread " +
                "FROM " + TABLE + " WHERE classification!='BASELINE' ORDER BY processed_at DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(Math.max(1, limit))})) {
            SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            while (c.moveToNext()) {
                String time = format.format(new Date(c.getLong(2)));
                String name = c.getString(0);
                String status = c.getString(1);
                int qr = c.getInt(3);
                int keywords = c.getInt(4);
                String matched = c.getString(5);
                boolean amountValue = c.getInt(6) != 0;
                boolean timeValue = c.getInt(7) != 0;
                boolean transactionValue = c.getInt(8) != 0;
                boolean layoutPass = c.getInt(9) != 0;
                float spread = c.getFloat(10);
                rows.add(time + "  " + name + "\n结果：" + status + "；QR=" + qr +
                        "；关键词=" + keywords + (matched == null || matched.isEmpty() ? "" : "（" + matched + "）") +
                        "\n金额值=" + amountValue + "；时间值=" + timeValue + "；单号值=" + transactionValue + "；版式=" + layoutPass +
                        "；文字纵向覆盖=" + (spread < 0f ? "未取得边界框" : String.format(Locale.CHINA, "%.0f%%", spread * 100f)));
            }
        }
        return rows;
    }


    public static final class PendingRecord {
        public final String uri;
        public final String name;
        public final String classification;
        public final int qrCount;
        public final String matchedKeywords;
        public final String reviewState;
        public final long processedAt;
        PendingRecord(String uri, String name, String classification, int qrCount, String matchedKeywords, String reviewState, long processedAt) {
            this.uri = uri; this.name = name; this.classification = classification;
            this.qrCount = qrCount; this.matchedKeywords = matchedKeywords; this.reviewState = reviewState; this.processedAt = processedAt;
        }
    }

    public synchronized List<PendingRecord> getPendingRecords(int limit) {
        List<PendingRecord> rows = new ArrayList<>();
        String sql = "SELECT uri,display_name,classification,qr_count,matched_keywords,review_state,processed_at FROM " + TABLE +
                " WHERE review_state IN ('WAITING','SHARE_OPENED') ORDER BY processed_at DESC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(Math.max(1, limit))})) {
            while (c.moveToNext()) rows.add(new PendingRecord(c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getString(4), c.getString(5), c.getLong(6)));
        }
        return rows;
    }

    public synchronized void setReviewState(String uri, String state) {
        ContentValues values = new ContentValues();
        values.put("review_state", state);
        getWritableDatabase().update(TABLE, values, "uri=?", new String[]{uri});
    }

    @Override public synchronized void close() { super.close(); }

    public synchronized int getResultCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE classification!='BASELINE'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
