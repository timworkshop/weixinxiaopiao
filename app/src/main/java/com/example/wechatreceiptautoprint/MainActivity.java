package com.example.wechatreceiptautoprint;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA = 10;
    private static final int REQ_NOTIFICATIONS = 11;
    private TextView status;
    private LinearLayout pendingContainer;
    private ReceiptRecordDb recordDb;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        recordDb = new ReceiptRecordDb(this);
        buildUi();
        refreshPending();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 32, 28, 32);
        status = new TextView(this);
        status.setTextSize(16);
        status.setText("微信收款小票识别助手 V0.3.0\n识别通过后进入待确认列表。只有你点击确认，才会把图片交给打印应用；本应用不会自动启动打印。\n监测目录：Pictures/WeiXin/mmexport*.jpg/jpeg");
        Button start = new Button(this); start.setText("开始监测");
        Button stop = new Button(this); stop.setText("停止监测");
        Button refresh = new Button(this); refresh.setText("刷新待确认列表");
        Button records = new Button(this); records.setText("查看识别记录");
        TextView heading = new TextView(this); heading.setText("\n待人工确认的小票"); heading.setTextSize(20);
        pendingContainer = new LinearLayout(this); pendingContainer.setOrientation(LinearLayout.VERTICAL);
        layout.addView(status); layout.addView(start); layout.addView(stop); layout.addView(refresh);
        layout.addView(records); layout.addView(heading); layout.addView(pendingContainer);
        scroll.addView(layout); setContentView(scroll);
        start.setOnClickListener(v -> checkMediaPermissionAndStart());
        stop.setOnClickListener(v -> { stopService(new Intent(this, ReceiptMonitorService.class)); status.setText("监测已停止。识别记录仍保留在本机。"); });
        refresh.setOnClickListener(v -> refreshPending());
        records.setOnClickListener(v -> showRecords());
    }

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    private void checkMediaPermissionAndStart() {
        String permission = mediaPermission();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{permission}, REQ_MEDIA); return; }
        checkNotificationPermissionThenStart();
    }
    private void checkNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS); return;
        }
        startMonitor(true);
    }
    private void startMonitor(boolean notificationsAllowed) {
        try {
            Intent i = new Intent(this, ReceiptMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            status.setText("监测已启动。请保存一张新的微信收款小票。\n识别结果只进入待确认列表，不会自动打印。\n本地识别记录数：" + recordDb.getResultCount() +
                    (notificationsAllowed ? "" : "\n通知权限未授予；请在系统设置中开启通知。"));
        } catch (RuntimeException e) { status.setText("启动监测失败：" + e.getClass().getSimpleName() + "。请检查图片权限及系统后台限制。"); }
    }

    private void refreshPending() {
        if (pendingContainer == null || recordDb == null) return;
        pendingContainer.removeAllViews();
        List<ReceiptRecordDb.PendingRecord> rows = recordDb.getPendingRecords(50);
        if (rows.isEmpty()) {
            TextView empty = new TextView(this); empty.setText("目前没有待确认的小票。保存新的微信收款小票后，可在这里核对。"); empty.setPadding(0, 12, 0, 12); pendingContainer.addView(empty); return;
        }
        for (ReceiptRecordDb.PendingRecord row : rows) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(12, 12, 12, 16);
            TextView info = new TextView(this); info.setTextSize(15);
            String date = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(row.processedAt));
            info.setText(date + "\n" + row.name + "\n判断：" + ("PROBABLE_RECEIPT".equals(row.classification) ? "疑似收款小票" : "需要人工复核") +
                    ("SHARE_OPENED".equals(row.reviewState) ? "\n状态：已打开分享面板，尚不代表打印成功；若已打印请勿重复操作" : "\n状态：待人工确认") +
                    "\n二维码：" + row.qrCount + "；关键词：" + (row.matchedKeywords == null ? "无" : row.matchedKeywords));
            Button review = new Button(this); review.setText("查看图片并确认"); review.setOnClickListener(v -> showReviewDialog(row));
            Button reject = new Button(this); reject.setText("忽略此图片"); reject.setOnClickListener(v -> {
                recordDb.setReviewState(row.uri, "IGNORED"); refreshPending();
            });
            card.addView(info); card.addView(review); card.addView(reject);
            View line = new View(this); line.setBackgroundColor(0xFFDDDDDD); line.setMinimumHeight(1);
            pendingContainer.addView(card); pendingContainer.addView(line);
        }
    }

    private void showReviewDialog(ReceiptRecordDb.PendingRecord row) {
        Uri uri = Uri.parse(row.uri);
        String detail = "文件：" + row.name + "\n识别分类：" + row.classification + "\n关键词：" + row.matchedKeywords +
                "\n\n请先核对原图。点击下一步只会打开系统分享面板，由你选择 Thermer 等打印应用；是否真正打印由打印应用决定。";
        new AlertDialog.Builder(this).setTitle("人工核对小票").setMessage(detail)
                .setNegativeButton("暂不处理", null)
                .setNeutralButton("忽略", (d, w) -> { recordDb.setReviewState(row.uri, "IGNORED"); refreshPending(); })
                .setPositiveButton("查看原图", (d, w) -> showImageAndShare(row, uri)).show();
    }

    private void showImageAndShare(ReceiptRecordDb.PendingRecord row, Uri uri) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 8, 16, 8);
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMaxHeight(getResources().getDisplayMetrics().heightPixels / 2);
        try { preview.setImageURI(uri); }
        catch (Exception e) { Toast.makeText(this, "原图预览失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        TextView note = new TextView(this);
        note.setText("文件：" + row.name + "\n\n请确认这确实是本次收款小票。下一步只打开系统分享面板，不会直接打印。你还需要在 Thermer 等打印应用内再次确认。");
        note.setPadding(0, 12, 0, 4);
        content.addView(preview);
        content.addView(note);
        new AlertDialog.Builder(this).setTitle("核对小票原图").setView(content)
                .setNegativeButton("取消", null)
                .setNeutralButton("忽略", (d, w) -> { recordDb.setReviewState(row.uri, "IGNORED"); refreshPending(); })
                .setPositiveButton("确认并选择打印应用", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, "选择打印应用"));
                        // This records that the share chooser opened, not that a print completed. Keep it visible for recovery.
                        recordDb.setReviewState(row.uri, "SHARE_OPENED");
                        refreshPending();
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开分享面板：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void showRecords() {
        List<String> rows = recordDb.getRecentSummaries(30);
        if (rows.isEmpty()) { new AlertDialog.Builder(this).setTitle("识别记录").setMessage("目前没有识别记录。首次启动只建立旧图片基线；请保存新图片进行测试。").setPositiveButton("知道了", null).show(); return; }
        StringBuilder text = new StringBuilder(); for (String row : rows) text.append(row).append("\n\n");
        new AlertDialog.Builder(this).setTitle("最近识别记录（最多 30 条）").setMessage(text.toString()).setPositiveButton("关闭", null).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MEDIA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) checkNotificationPermissionThenStart();
            else status.setText("未获得图片读取权限，无法监测 Pictures/WeiXin/。");
        } else if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED; startMonitor(granted);
        }
    }
    @Override protected void onResume() { super.onResume(); if (recordDb != null) refreshPending(); }
    @Override protected void onDestroy() { if (recordDb != null) recordDb.close(); super.onDestroy(); }
}
