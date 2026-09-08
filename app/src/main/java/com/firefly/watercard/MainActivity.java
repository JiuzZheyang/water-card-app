package com.firefly.watercard;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "WaterCard";

    // Helper to avoid int-to-byte narrowing issues
    private static byte[] b(int... vals) {
        byte[] r = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) r[i] = (byte) vals[i];
        return r;
    }

    // 常见水卡 AID 列表
    private static final byte[][] COMMON_AIDS = {
            b(0xD2, 0x76, 0x00, 0x01, 0x24, 0x01, 0x02, 0x00),
            b(0xA0, 0x00, 0x00, 0x03, 0x06, 0x00),
            b(0xA0, 0x00, 0x00, 0x02, 0x01, 0x01),
            b(0x00, 0x00, 0x00, 0x04, 0x30, 0x00),
            b(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
    };

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;

    private TextView tvStatus, tvCardInfo, tvUid, tvAts;
    private TextView tvRawData, tvBalance, tvLog;
    private Button btnRetry, btnSave;

    private StringBuilder logBuilder = new StringBuilder();
    private String lastRawData = "", lastUid = "", lastAts = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        initNfc();
        handleIntent(getIntent());
    }

    private void initViews() {
        tvStatus   = findViewById(R.id.tv_status);
        tvCardInfo = findViewById(R.id.tv_card_info);
        tvUid      = findViewById(R.id.tv_uid);
        tvAts      = findViewById(R.id.tv_ats);
        tvRawData  = findViewById(R.id.tv_raw_data);
        tvBalance  = findViewById(R.id.tv_balance);
        tvLog      = findViewById(R.id.tv_log);
        btnRetry   = findViewById(R.id.btn_retry);
        btnSave    = findViewById(R.id.btn_save);

        btnRetry.setOnClickListener(v -> {
            appendLog("请将水卡贴在手机 NFC 天线位置...");
            tvStatus.setText("请贴卡...");
        });
        btnSave.setOnClickListener(v -> saveDataToFile());
    }

    private void initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            tvStatus.setText("❌ 设备不支持 NFC");
            appendLog("错误：此设备没有 NFC 硬件");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            tvStatus.setText("⚠️ 请先开启 NFC");
            appendLog("请到系统设置 → NFC → 开启 NFC");
            return;
        }
        tvStatus.setText("✅ NFC 就绪，请贴卡");
        appendLog("NFC 已就绪，请将水卡贴在手机背面 NFC 天线处");
        pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent,
                    new IntentFilter[]{new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)}, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) processTag(tag);
        }
    }

    private void processTag(Tag tag) {
        runOnUiThread(() -> tvStatus.setText("📡 检测到卡片，正在读取..."));
        appendLog("检测到卡片");
        byte[] uid = tag.getId();
        lastUid = uid != null ? bytesToHex(uid) : "无";
        tvUid.setText("UID: " + lastUid);
        appendLog("UID: " + lastUid);
        tvCardInfo.setText("技术: " + Arrays.toString(tag.getTechList()));

        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            try {
                isoDep.connect();
                byte[] hb = isoDep.getHistoricalBytes();
                lastAts = hb != null ? bytesToHex(hb) : "无";
                tvAts.setText("ATS: " + lastAts);
                if (hb != null) appendLog("ATS: " + lastAts);
                readIsoDepCard(isoDep);
            } catch (Exception e) {
                Log.e(TAG, "IsoDep 错误", e);
                appendLog("IsoDep 连接失败: " + e.getMessage());
                runOnUiThread(() -> tvStatus.setText("❌ 连接失败"));
            } finally {
                try { isoDep.close(); } catch (IOException ignored) {}
            }
        } else {
            appendLog("尝试 NfcA...");
            tryNfcA(tag);
        }
    }

    private void readIsoDepCard(IsoDep isoDep) throws IOException {
        StringBuilder allData = new StringBuilder();
        boolean foundApp = false;

        for (int i = 0; i < COMMON_AIDS.length; i++) {
            byte[] aid = COMMON_AIDS[i];
            appendLog("尝试 AID #" + (i + 1) + ": " + bytesToHex(aid));
            byte[] resp = isoDep.transceive(buildSelectApdu(aid));
            if (resp != null && resp.length >= 2) {
                int sw1 = resp[resp.length - 2] & 0xFF;
                int sw2 = resp[resp.length - 1] & 0xFF;
                appendLog("  响应: " + String.format("%02X%02X", sw1, sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    appendLog("  ✅ 应用选择成功!");
                    foundApp = true;
                    readCardFiles(isoDep, allData);
                    break;
                }
            }
        }
        if (!foundApp) {
            appendLog("未匹配 AID，尝试通用探测...");
            tryGenericIsoDepRead(isoDep, allData);
        }
        lastRawData = allData.toString();
        runOnUiThread(() -> {
            tvRawData.setText(lastRawData.isEmpty() ? "（无数据）" : lastRawData);
            tvStatus.setText(foundApp ? "✅ 读取完成" : "⚠️ 部分读取");
        });
    }

    private byte[] buildSelectApdu(byte[] aid) {
        byte[] apdu = new byte[6 + aid.length];
        apdu[0] = 0x00; apdu[1] = 0xA4; apdu[2] = 0x04; apdu[3] = 0x00;
        apdu[4] = (byte) aid.length;
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        apdu[5 + aid.length] = 0x00;
        return apdu;
    }

    private void readCardFiles(IsoDep isoDep, StringBuilder output) throws IOException {
        appendLog("--- 读取卡片文件 ---");
        byte[][] cmds = {
                b(0x00, 0xB0, 0x00, 0x00, 0x00),
                b(0x00, 0xB0, 0x00, 0x10, 0x00),
                b(0x00, 0xB0, 0x00, 0x14, 0x00),
                b(0x00, 0xB0, 0x00, 0x18, 0x00),
                b(0x00, 0xB0, 0x00, 0x1C, 0x00),
                b(0x00, 0xB0, 0x00, 0x20, 0x00),
                b(0x00, 0xB2, 0x01, 0xD0, 0x00),
                b(0x00, 0xB2, 0x02, 0xD0, 0x00),
                b(0x80, 0x5C, 0x00, 0x01, 0x00),
                b(0x00, 0xCA, 0x01, 0x00, 0x00),
                b(0x80, 0x84, 0x00, 0x00, 0x00),
        };
        for (byte[] cmd : cmds) {
            try {
                appendLog("发送: " + bytesToHex(cmd));
                byte[] resp = isoDep.transceive(cmd);
                if (resp != null && resp.length >= 2) {
                    int sw1 = resp[resp.length - 2] & 0xFF;
                    int sw2 = resp[resp.length - 1] & 0xFF;
                    byte[] data = resp.length > 2 ? Arrays.copyOf(resp, resp.length - 2) : new byte[0];
                    appendLog("  响应: " + bytesToHex(data) + " [" + String.format("%02X%02X", sw1, sw2) + "]");
                    output.append("CMD:").append(bytesToHex(cmd)).append(" DATA:").append(bytesToHex(data)).append("\n");
                    if (sw1 == 0x90 && sw2 == 0x00 && data.length > 0) parseAndShowBalance(data);
                }
            } catch (Exception e) { appendLog("  命令失败: " + e.getMessage()); }
        }
    }

    private void tryGenericIsoDepRead(IsoDep isoDep, StringBuilder output) throws IOException {
        appendLog("--- 通用探测 ---");
        for (int sfi = 0; sfi <= 7; sfi++) {
            for (int f = 0; f <= 0x1F; f++) {
                byte[] cmd = b(0x00, 0xB0, 0x80 | f, (sfi << 3) | 0x04, 0x00);
                try {
                    byte[] resp = isoDep.transceive(cmd);
                    if (resp != null && resp.length >= 2 && resp[resp.length - 2] == (byte)0x90 && resp[resp.length - 1] == 0x00) {
                        byte[] data = Arrays.copyOf(resp, resp.length - 2);
                        if (data.length > 0) {
                            appendLog("文件 F=" + String.format("%02X", f) + " SFI=" + sfi + ": " + bytesToHex(data));
                            output.append("FILE").append(String.format("%02X", f)).append("SFI").append(String.valueOf(sfi)).append(":").append(bytesToHex(data)).append("\n");
                            parseAndShowBalance(data);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void tryNfcA(Tag tag) {
        NfcA nfcA = NfcA.get(tag);
        if (nfcA == null) {
            appendLog("不支持 NfcA");
            runOnUiThread(() -> tvStatus.setText("❌ 不支持的卡片类型"));
            return;
        }
        try {
            nfcA.connect();
            appendLog("NfcA ATQA:" + bytesToHex(nfcA.getAtqa()) + " SAK:" + String.format("%02X", nfcA.getSak()));
            byte[] resp = nfcA.transceive(b(0x30, 0x00));
            if (resp != null) appendLog("块0: " + bytesToHex(resp));
            nfcA.close();
        } catch (Exception e) { appendLog("NfcA 失败: " + e.getMessage()); }
    }

    private void parseAndShowBalance(byte[] data) {
        if (data == null || data.length < 4) return;
        appendLog("--- 尝试解析余额 ---");
        appendLog("原始: " + bytesToHex(data));
        if (data.length >= 4) {
            long bal1 = ((data[0] & 0xFF) * 10000 + (data[1] & 0xFF) * 100 + (data[2] & 0xFF));
            runOnUiThread(() -> tvBalance.setText("💧 余额: " + String.format("%.2f", bal1 / 100.0) + " 元"));
            appendLog("余额: " + String.format("%.2f", bal1 / 100.0) + " 元");
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuilder.insert(0, "[" + ts + "] " + msg + "\n");
        if (logBuilder.length() > 4000) logBuilder.setLength(4000);
        runOnUiThread(() -> tvLog.setText(logBuilder.toString()));
    }

    private void saveDataToFile() {
        if (lastRawData.isEmpty()) return;
        String fn = "water_card_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        File dir = new File("/sdcard/water_card_dump/");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, fn);
        try (FileWriter fw = new FileWriter(f)) {
            fw.write("=== 水卡读取数据 ===\n");
            fw.write("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            fw.write("UID: " + lastUid + "\n");
            fw.write("ATS: " + lastAts + "\n");
            fw.write("=== HEX 数据 ===\n");
            fw.write(lastRawData);
        } catch (IOException e) { appendLog("保存失败: " + e.getMessage()); return; }
        appendLog("已保存到: " + f.getAbsolutePath());
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
