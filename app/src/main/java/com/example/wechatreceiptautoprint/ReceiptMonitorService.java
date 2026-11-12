package com.example.wechatreceiptautoprint;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** V0.2.4: QR-first + multi-field OCR evidence + Unicode normalization + persistent history. No printing code exists. */
public class ReceiptMonitorService extends Service {
    private static final String TAG = "ReceiptMonitorV02";
    private static final String SERVICE_CHANNEL = "receipt_monitor_service";
    private static final String RESULT_CHANNEL = "receipt_monitor_results";
    private static final int FOREGROUND_ID = 1001;
    private static final int MAX_BITMAP_SIDE = 3072;
    private static final String PREFS = "monitor_state";
    private static final String PREF_BASELINE_DONE = "initial_baseline_done";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> processing = Collections.synchronizedSet(new HashSet<>());
    // Retry counters are touched only on the main thread.
    private final Map<String, Integer> retryCounts = new HashMap<>();
    private ContentObserver observer;
    private ExecutorService scanExecutor;
    private ExecutorService decodeExecutor;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private ReceiptRecordDb recordDb;
    private volatile boolean destroyed = false;

    private final Runnable delayedScan = () -> {
        scheduleScan();
        // Some Gallery implementations publish metadata/file bytes in separate steps.
        handler.postDelayed(this::scheduleScan, 1800);
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(FOREGROUND_ID, buildServiceNotification("监测微信图片；二维码 + OCR 小票过滤（不打印）"));

        recordDb = new ReceiptRecordDb(this);
        SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!state.contains("baseline_start_epoch_seconds")) {
            state.edit().putLong("baseline_start_epoch_seconds", System.currentTimeMillis() / 1000L).apply();
        }
        scanExecutor = Executors.newSingleThreadExecutor();
        decodeExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions barcodeOptions = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(barcodeOptions);
        textRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(delayedScan);
                handler.postDelayed(delayedScan, 1200);
            }
        };
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        scheduleScan();
        Log.i(TAG, "V0.3.0 service started. Existing media are baselined only on first run; later unseen files are processed.");
    }

    private void scheduleScan() {
        if (destroyed || scanExecutor == null || scanExecutor.isShutdown()) return;
        try {
            scanExecutor.execute(this::scanOnWorker);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule MediaStore scan", e);
        }
    }

    private void scanOnWorker() {
        if (destroyed) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean firstRun = !prefs.getBoolean(PREF_BASELINE_DONE, false);
        long baselineStartSeconds = prefs.getLong("baseline_start_epoch_seconds", System.currentTimeMillis() / 1000L);
        ContentResolver resolver = getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        boolean modernMediaStore = Build.VERSION.SDK_INT >= 29;
        String pathColumn = modernMediaStore ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
        String[] projection;
        String selection;
        String[] args;
        if (modernMediaStore) {
            projection = new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.IS_PENDING};
            selection = pathColumn + "=? AND " + MediaStore.Images.Media.IS_PENDING + "=0";
            args = new String[] {"Pictures/WeiXin/"};
        } else {
            projection = new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA, MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_ADDED};
            selection = pathColumn + " LIKE ?";
            args = new String[] {"%/Pictures/WeiXin/%"};
        }

        boolean queryCompleted = false;
        try (Cursor c = resolver.query(collection, projection, selection, args,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) {
                Log.w(TAG, "MediaStore returned null cursor; baseline flag unchanged.");
                return;
            }
            while (c.moveToNext()) {
                if (destroyed) break;
                String name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME));
                String mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE));
                String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (!lowerName.startsWith("mmexport") ||
                        !(lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) ||
                        (mime != null && !mime.equalsIgnoreCase("image/jpeg"))) continue;

                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                Uri imageUri = ContentUris.withAppendedId(collection, id);
                String key = imageUri.toString();
                long dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED));
                if (firstRun && dateAdded < baselineStartSeconds) {
                    recordDb.insertBaseline(key, name);
                    continue;
                }
                if (!recordDb.hasRecord(key) && processing.add(key)) {
                    int listedWidth = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH));
                    int listedHeight = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT));
                    Log.i(TAG, "New candidate: " + name + " listed=" + listedWidth + "x" + listedHeight + " uri=" + key);
                    analyzeImage(name, imageUri, key);
                }
            }
            queryCompleted = !destroyed;
        } catch (SecurityException e) {
            Log.e(TAG, "MediaStore permission denied. Check READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE (Android 12).", e);
        } catch (Exception e) {
            Log.e(TAG, "MediaStore scan failed", e);
        }

        if (firstRun && queryCompleted) {
            prefs.edit().putBoolean(PREF_BASELINE_DONE, true).apply();
            Log.i(TAG, "Initial baseline saved. Existing images will not be analyzed; later unseen images will.");
        }
    }

    private void analyzeImage(String name, Uri uri, String key) {
        try {
            decodeExecutor.execute(() -> {
                ImagePayload payload = null;
                try {
                    payload = loadImage(uri);
                    if (destroyed) {
                        release(payload);
                        processing.remove(key);
                        return;
                    }
                    final ImagePayload loaded = payload;
                    Log.i(TAG, "Image decoded: " + name + "=" + loaded.bitmap.getWidth() + "x" +
                            loaded.bitmap.getHeight() + " EXIF=" + loaded.rotationDegrees);
                    barcodeScanner.process(loaded.inputImage)
                            .addOnSuccessListener(barcodes -> {
                                if (destroyed) { release(loaded); processing.remove(key); return; }
                                int qrCount = 0;
                                for (Barcode barcode : barcodes) {
                                    if (barcode.getFormat() == Barcode.FORMAT_QR_CODE) qrCount++;
                                }
                                // Deliberately do not read/log/store raw QR contents.
                                Log.i(TAG, "QR stage: " + name + " qrCount=" + qrCount + " payload=omitted");
                                if (qrCount == 0) {
                                    saveAndFinish(key, name, loaded, 0, 0, "", 0, 0,
                                            false, false, false, false, 0f, "NO_QR");
                                } else {
                                    try {
                                        runOcr(name, key, loaded, qrCount);
                                    } catch (Exception e) {
                                        handleFailure(name, key, loaded, "Could not start Chinese OCR", e);
                                    } catch (OutOfMemoryError e) {
                                        handleFailure(name, key, loaded, "OCR ran out of memory", e);
                                    }
                                }
                            })
                            .addOnFailureListener(e -> handleFailure(name, key, loaded, "QR scan failed", e));
                } catch (Exception e) {
                    handleFailure(name, key, payload, "Image decode failed", e);
                } catch (OutOfMemoryError e) {
                    handleFailure(name, key, payload, "Image decode ran out of memory", e);
                }
            });
        } catch (RuntimeException e) {
            processing.remove(key);
            Log.e(TAG, "Decode executor rejected image " + name, e);
        }
    }

    private ImagePayload loadImage(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        int rotation = 0;
        try (InputStream exifStream = resolver.openInputStream(uri)) {
            if (exifStream != null) rotation = new ExifInterface(exifStream).getRotationDegrees();
        } catch (Exception e) {
            Log.w(TAG, "EXIF unavailable; rotation defaults to 0 for " + uri, e);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream boundsStream = resolver.openInputStream(uri)) {
            if (boundsStream == null) throw new IllegalStateException("Cannot open image stream");
            BitmapFactory.decodeStream(boundsStream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("Invalid image bounds " + bounds.outWidth + "x" + bounds.outHeight);
        }

        int sample = 1;
        while ((bounds.outWidth / sample > MAX_BITMAP_SIDE || bounds.outHeight / sample > MAX_BITMAP_SIDE) && sample < 1024) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream imageStream = resolver.openInputStream(uri)) {
            if (imageStream == null) throw new IllegalStateException("Cannot reopen image stream");
            bitmap = BitmapFactory.decodeStream(imageStream, null, decode);
        }
        if (bitmap == null) throw new IllegalArgumentException("Bitmap decode returned null");
        InputImage inputImage = InputImage.fromBitmap(bitmap, rotation);
        return new ImagePayload(bitmap, inputImage, rotation);
    }

    private void runOcr(String name, String key, ImagePayload payload, int qrCount) {
        textRecognizer.process(payload.inputImage)
                .addOnSuccessListener(result -> {
                    if (destroyed) { release(payload); processing.remove(key); return; }
                    String raw = result.getText() == null ? "" : result.getText();
                    int blockCount = result.getTextBlocks().size();
                    int lineCount = 0;
                    for (Text.TextBlock block : result.getTextBlocks()) lineCount += block.getLines().size();
                    int rotatedHeight = (payload.rotationDegrees == 90 || payload.rotationDegrees == 270)
                            ? payload.bitmap.getWidth() : payload.bitmap.getHeight();
                    float verticalSpread = calculateVerticalSpread(result, rotatedHeight);
                    ReceiptHeuristics.Result decision = ReceiptHeuristics.classify(
                            raw, blockCount, lineCount, verticalSpread);
                    String classification = decision.classification;

                    Log.i(TAG, "OCR stage: " + name + " keywordCount=" + decision.keywordCount +
                            " keywords=[" + decision.matchedKeywords + "] blocks=" + decision.blockCount +
                            " lines=" + decision.lineCount + " amountValue=" + decision.amountValue +
                            " timeValue=" + decision.timeValue + " transactionValue=" + decision.transactionValue + " verticalSpread=" +
                            decision.verticalSpreadRatio + " layoutPass=" + decision.layoutPass +
                            " classification=" + classification +
                            " rawText=omitted");

                    saveAndFinish(key, name, payload, qrCount, decision.keywordCount, decision.matchedKeywords,
                            decision.blockCount, decision.lineCount, decision.amountValue, decision.timeValue,
                            decision.transactionValue, decision.layoutPass, decision.verticalSpreadRatio, classification);
                    if ("PROBABLE_RECEIPT".equals(classification)) {
                        showResultNotification("疑似微信收款小票（未打印）",
                                name + "：检测到二维码及 " + decision.keywordCount + " 项小票关键词，请人工核验");
                    } else if ("REVIEW".equals(classification)) {
                        showResultNotification("二维码图片待人工核验（未打印）",
                                name + "：小票特征不完整，请在应用记录中人工核验");
                    }
                })
                .addOnFailureListener(e -> handleFailure(name, key, payload, "Chinese OCR failed", e));
    }

    private float calculateVerticalSpread(Text result, int imageHeight) {
        if (imageHeight <= 0) return -1f;
        int minTop = Integer.MAX_VALUE;
        int maxBottom = Integer.MIN_VALUE;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box != null) {
                    minTop = Math.min(minTop, box.top);
                    maxBottom = Math.max(maxBottom, box.bottom);
                }
            }
        }
        if (minTop == Integer.MAX_VALUE || maxBottom <= minTop) return -1f;
        return Math.min(1f, Math.max(0f, (float) (maxBottom - minTop) / (float) imageHeight));
    }

    private void saveAndFinish(String key, String name, ImagePayload payload, int qrCount,
                               int keywordCount, String matched, int blocks, int lines,
                               boolean amountValue, boolean timeValue, boolean transactionValue, boolean layoutPass,
                               float verticalSpread, String classification) {
        int width = payload == null ? 0 : payload.bitmap.getWidth();
        int height = payload == null ? 0 : payload.bitmap.getHeight();
        try {
            recordDb.saveResult(key, name, width, height, qrCount, keywordCount,
                    matched, blocks, lines, amountValue, timeValue, transactionValue, layoutPass, verticalSpread, classification);
        } catch (Exception e) {
            Log.e(TAG, "Could not save recognition result for " + name, e);
        }
        retryCounts.remove(key);
        release(payload);
        processing.remove(key);
    }

    private void handleFailure(String name, String key, ImagePayload payload, String stage, Throwable error) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> handleFailure(name, key, payload, stage, error));
            return;
        }
        if (destroyed) {
            release(payload);
            processing.remove(key);
            return;
        }
        Log.e(TAG, stage + " for " + name, error);
        release(payload);
        int count = retryCounts.containsKey(key) ? retryCounts.get(key) + 1 : 1;
        retryCounts.put(key, count);
        processing.remove(key);
        if (count <= 2) {
            Log.w(TAG, "Will retry candidate " + name + " attempt=" + (count + 1));
            handler.postDelayed(this::scheduleScan, 1800L * count);
        } else {
            try {
                recordDb.saveResult(key, name, 0, 0, -1, 0, "", 0, 0,
                        false, false, false, false, 0f, "ERROR");
            } catch (Exception dbError) {
                Log.e(TAG, "Could not save failed recognition record", dbError);
            }
            retryCounts.remove(key);
            showResultNotification("图片识别失败（未打印）", name + "：连续失败 3 次，请检查图片或 Logcat");
        }
    }

    private void release(ImagePayload payload) {
        if (payload != null && payload.bitmap != null && !payload.bitmap.isRecycled()) payload.bitmap.recycle();
    }

    private void showResultNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Intent open = new Intent(this, MainActivity.class);
            int id = (int) (System.currentTimeMillis() & 0x7fffffff);
            PendingIntent pi = PendingIntent.getActivity(this, id, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, RESULT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(title).setContentText(text).setContentIntent(pi)
                    .setAutoCancel(true).build();
            nm.notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission unavailable; result remains in local history.", e);
        }
    }

    private Notification buildServiceNotification(String text) {
        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("微信小票识别助手 V0.3.0")
                .setContentText(text).setOngoing(true).build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(SERVICE_CHANNEL,
                    "小票监测服务", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL,
                    "小票识别结果", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacksAndMessages(null);
        if (barcodeScanner != null) barcodeScanner.close();
        if (textRecognizer != null) textRecognizer.close();
        if (scanExecutor != null) scanExecutor.shutdownNow();
        if (decodeExecutor != null) decodeExecutor.shutdownNow();
        if (recordDb != null) recordDb.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class ImagePayload {
        final Bitmap bitmap;
        final InputImage inputImage;
        final int rotationDegrees;
        ImagePayload(Bitmap bitmap, InputImage inputImage, int rotationDegrees) {
            this.bitmap = bitmap;
            this.inputImage = inputImage;
            this.rotationDegrees = rotationDegrees;
        }
    }
}
