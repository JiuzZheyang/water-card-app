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
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "WaterCard";

    private static byte[] b(int... vals) {
        byte[] r = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) r[i] = (byte) vals[i];
        return r;
    }

    private static final byte[] KNOWN_CPU_CMD = b(
        0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x44, (byte)0xA3,
        0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA
    );

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
        try {
            setContentView(R.layout.activity_main);
            initViews();
            initNfc();
            handleIntent(getIntent());
        } catch (Exception e) {
            Log.e(TAG, "onCreate crashed", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            try { setContentView(R.layout.activity_main); } catch (Exception ignored) {}
            initViews();
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvCardInfo = findViewById(R.id.tv_card_info);
        tvUid = findViewById(R.id.tv_uid);
        tvAts = findViewById(R.id.tv_ats);
        tvRawData = findViewById(R.id.tv_raw_data);
        tvBalance = findViewById(R.id.tv_balance);
        tvLog = findViewById(R.id.tv_log);
        btnRetry = findViewById(R.id.btn_retry);
        btnSave = findViewById(R.id.btn_save);
        btnRetry.setOnClickListener(v -> {
            appendLog("Please place card on NFC antenna...");
            tvStatus.setText("Waiting for card...");
        });
        btnSave.setOnClickListener(v -> saveDataToFile());
    }

    private void initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            tvStatus.setText("NFC not supported");
            appendLog("No NFC hardware");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            tvStatus.setText("Please enable NFC");
            appendLog("Enable NFC in settings");
            return;
        }
        tvStatus.setText("NFC Ready");
        appendLog("NFC ready. Place card on phone NFC area");
        pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.enableReaderMode(this,
                (tag) -> runOnUiThread(() -> handleTag(tag)),
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null);
        } catch (Exception e) {
            Log.e(TAG, "enableReaderMode failed, trying foregroundDispatch", e);
            try {
                pendingIntent = PendingIntent.getActivity(this, 0,
                    new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE);
                nfcAdapter.enableForegroundDispatch(this, pendingIntent,
                    new IntentFilter[]{new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)}, null);
            } catch (Exception e2) {
                Log.e(TAG, "enableForegroundDispatch also failed", e2);
                appendLog("NFC init failed: " + e2.getMessage());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableReaderMode(this);
        } catch (Exception e) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            handleIntent(intent);
        } catch (Exception e) {
            Log.e(TAG, "onNewIntent crashed", e);
            Toast.makeText(this, "NFC Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction()) ||
            NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction()) ||
            NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) handleTag(tag);
        }
    }

    private void handleTag(Tag tag) {
        appendLog("Card detected");
        byte[] uid = tag.getId();
        lastUid = uid != null ? bytesToHex(uid) : "null";
        tvUid.setText("UID: " + lastUid);
        appendLog("UID: " + lastUid);
        tvCardInfo.setText("Tech: " + Arrays.toString(tag.getTechList()));
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep != null) {
            try {
                isoDep.setTimeout(1500);
                isoDep.connect();
                Thread.sleep(50);
                byte[] hb = isoDep.getHistoricalBytes();
                lastAts = hb != null ? bytesToHex(hb) : "null";
                tvAts.setText("ATS: " + lastAts);
                if (hb != null) appendLog("ATS: " + lastAts);
                tvStatus.setText("Reading...");
                readIsoDepCard(isoDep);
            } catch (Exception e) {
                Log.e(TAG, "IsoDep error", e);
                appendLog("IsoDep connect failed: " + e.getMessage());
                tvStatus.setText("Connection failed");
            } finally {
                try { isoDep.close(); } catch (IOException ignored) {}
            }
        } else {
            appendLog("Trying NfcA...");
            tryNfcA(tag);
        }
        autoSaveDataToFile();
    }

    private void readIsoDepCard(IsoDep isoDep) throws IOException {
        StringBuilder allData = new StringBuilder();
        boolean gotData = false;

        appendLog("=== STEP 0: KNOWN CPU COMMAND (BEFORE SELECT) ===");
        try {
            byte[] r0 = isoDep.transceive(KNOWN_CPU_CMD);
            logResp("KNOWN_CPU", KNOWN_CPU_CMD, r0);
            if (r0 != null && r0.length >= 2) {
                int sw = sw(r0);
                appendLog("  SW=" + String.format("%04X", sw) + " len=" + r0.length);
                if (sw == 0x4AF1 || sw == 0x9000) {
                    byte[] d = dataOf(r0);
                    appendLog("  Data: " + bytesToHex(d) + " (" + d.length + " bytes)");
                    if (d.length > 0 && !isAllZeros(d)) {
                        appendLog("SUCCESS! Balance: " + bytesToHex(d));
                        allData.append("CPU_DATA:").append(bytesToHex(d)).append("\n");
                        parseAndShowBalance(d);
                        gotData = true;
                    }
                } else if (sw >= 0x6C00) {
                    // Le wrong, card wants specific length
                    int correctLe = sw & 0xFF;
                    appendLog("  Le wrong! Card wants " + correctLe + " bytes, retrying...");
                    byte[] cmd2 = Arrays.copyOf(KNOWN_CPU_CMD, KNOWN_CPU_CMD.length);
                    cmd2[cmd2.length - 1] = (byte)correctLe;
                    byte[] r2 = isoDep.transceive(cmd2);
                    logResp("CPU_RETRY", cmd2, r2);
                    if (r2 != null && (sw(r2) == 0x4AF1 || sw(r2) == 0x9000)) {
                        byte[] d2 = dataOf(r2);
                        appendLog("  SUCCESS on retry! Data: " + bytesToHex(d2));
                        allData.append("CPU_RETRY:").append(bytesToHex(d2)).append("\n");
                        parseAndShowBalance(d2);
                        gotData = true;
                    }
                } else {
                    appendLog("  SW not success: " + String.format("%04X", sw));
                }
            }
        } catch (Exception e) {
            appendLog("  KNOWN_CPU exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        appendLog("=== SELECT by File ID -> READ ===");
        int[] fileIds = {
            0x0005, 0x0006, 0x0007,
            0x0015, 0x0016, 0x0017,
            0x0025, 0x0026, 0x0027,
            0x0035, 0x0036, 0x0037,
            0x0001, 0x0002, 0x0003, 0x0004,
            0x0008, 0x0009, 0x000A, 0x000B,
            0x0010, 0x0011, 0x0012, 0x0013,
            0x0018, 0x0019, 0x001A,
            0x0020, 0x0021, 0x0022,
            0x0030, 0x0031, 0x0032,
            0x1001, 0x1002, 0x1003,
            0x2001, 0x2002,
        };
        for (int fid : fileIds) {
            byte hi = (byte)(fid >> 8);
            byte lo = (byte)(fid & 0xFF);
            byte[] sel = b(0x00, (byte)0xA4, 0x00, 0x00, 0x02, hi, lo);
            byte[] selResp = isoDep.transceive(sel);
            int selSW = selResp != null ? sw(selResp) : -1;
            logResp("SEL_" + String.format("%04X", fid), sel, selResp);
            if (selSW == 0x9000) {
                appendLog("  OK! SELECT " + String.format("%04X", fid) + " success");
                int[] leVals = {4, 16, 32, 0, 8, 64, 128, 255};
                for (int le : leVals) {
                    byte[] readCmd = b(0x00, (byte)0xB0,
                            (byte)(off >> 8), (byte)(off & 0xFF), (byte)le);
                    try {
                        byte[] rr = isoDep.transceive(readCmd);
                        byte[] rd = dataOf(rr);
                        int rsw = rr != null ? sw(rr) : -1;
                        // 6Cxx means "correct Le was xx bytes" - retry with correct length
                        if (rsw >= 0x6C00) {
                            int correctLe = rsw & 0xFF;
                            appendLog("    READ off=" + off + " Le wrong, card wants " + correctLe + " bytes");
                            byte[] rr2 = isoDep.transceive(b(0x00, (byte)0xB0, (byte)(off >> 8), (byte)(off & 0xFF), (byte)correctLe));
                            byte[] rd2 = dataOf(rr2);
                            int rsw2 = rr2 != null ? sw(rr2) : -1;
                            if (rsw2 == 0x9000 && rd2.length > 0 && !isAllZeros(rd2)) {
                                appendLog("    READ off=" + off + " Le=" + correctLe + " DATA=" + bytesToHex(rd2));
                                allData.append("R_").append(String.format("%04X", fid))
                                       .append("_L").append(correctLe)
                                       .append(":").append(bytesToHex(rd2)).append("\n");
                                parseAndShowBalance(rd2);
                                gotData = true;
                            }
                        } else if (rsw == 0x9000 && rd.length > 0 && !isAllZeros(rd)) {
                            appendLog("    READ off=" + off + " Le=" + le + " DATA=" + bytesToHex(rd));
                            allData.append("R_").append(String.format("%04X", fid))
                                   .append("_L").append(le)
                                   .append(":").append(bytesToHex(rd)).append("\n");
                            parseAndShowBalance(rd);
                            gotData = true;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        appendLog("=== CPU command Le variants (after SELECT尝试) ===");
        byte[][] aids = {
            b(0xD2, 0x76, 0x00, 0x01, 0x24, 0x01, 0x02, 0x00),
            b(0xA0, 0x00, 0x00, 0x03, 0x06),
            b(0xA0, 0x00, 0x00, 0x00, 0x03, 0x00),
            b(0x00, 0x00, 0x00, 0x04, 0x30, 0x00),
            b(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
        };
        for (byte[] aid : aids) {
            byte[] selAid = buildSelectApdu(aid);
            byte[] sr = isoDep.transceive(selAid);
            int aidSW = sr != null ? sw(sr) : -1;
            logResp("AID", selAid, sr);
            if (aidSW == 0x9000) {
                appendLog("  OK! AID success: " + bytesToHex(aid));
                for (int rec = 1; rec <= 30; rec++) {
                    for (int sfi = 0; sfi <= 31; sfi++) {
                        byte[] rrCmd = b(0x00, (byte)0xB2, rec,
                                (byte)((sfi << 3) | 0x04), 0x00);
                        try {
                            byte[] rrr = isoDep.transceive(rrCmd);
                            byte[] rrd = dataOf(rrr);
                            int rrsw = rrr != null ? sw(rrr) : -1;
                            if (rrd.length > 0 && !isAllZeros(rrd)) {
                                appendLog("    REC=" + rec + " SFI=" + sfi
                                        + " SW=" + String.format("%04X", rrsw)
                                        + " DATA=" + bytesToHex(rrd));
                                allData.append("REC").append(String.format("%02X", rec))
                                       .append("SFI").append(String.format("%02X", sfi))
                                       .append(":").append(bytesToHex(rrd)).append("\n");
                                parseAndShowBalance(rrd);
                                gotData = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                for (int off = 0; off < 256; off += 32) {
                    byte[] rbCmd = b(0x00, (byte)0xB0,
                            (byte)(off >> 8), (byte)(off & 0xFF), 0x00);
                    try {
                        byte[] rb = isoDep.transceive(rbCmd);
                        byte[] rbd = dataOf(rb);
                        int rbsw = rb != null ? sw(rb) : -1;
                        if (rbd.length > 0 && !isAllZeros(rbd)) {
                            appendLog("    BIN off=" + off + " SW=" + String.format("%04X", rbsw)
                                    + " DATA=" + bytesToHex(rbd));
                            allData.append("BINO").append(String.format("%02X", off))
                                   .append(":").append(bytesToHex(rbd)).append("\n");
                            parseAndShowBalance(rbd);
                            gotData = true;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        appendLog("=== CPU command variants (after SELECT) ===");
        byte[][] balCmds = {
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x04, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x10, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x20, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x80, 0x5C, 0x00, 0x01, 0x02),
            b(0x80, 0x5C, 0x00, 0x02, 0x02),
            b(0x00, (byte)0xCA, 0x01, 0x00, 0x00),
            b(0x80, (byte)0x84, 0x00, 0x00, 0x00),
        };
        for (byte[] cmd : balCmds) {
            try {
                byte[] r = isoDep.transceive(cmd);
                logResp("VAR", cmd, r);
                if (r != null && r.length >= 2) {
                    int csw = sw(r);
                    if (csw == 0x4AF1 || csw == 0x9000) {
                        byte[] cd = dataOf(r);
                        if (cd.length > 0) {
                            appendLog("OK! Variant cmd success! Data: " + bytesToHex(cd));
                            allData.append("VAR_DATA:").append(bytesToHex(cd)).append("\n");
                            parseAndShowBalance(cd);
                            gotData = true;
                        }
                    } else if (csw >= 0x6C00) {
                        int correctLe = csw & 0xFF;
                        appendLog("  Le wrong, card wants " + correctLe + " bytes");
                        byte[] r2 = isoDep.transceive(new byte[]{cmd[0], cmd[1], cmd[2], cmd[3], (byte)correctLe});
                        logResp("VAR_RETRY", r2 != null ? r2 : new byte[0], r2);
                        if (r2 != null && sw(r2) == 0x9000) {
                            byte[] cd2 = dataOf(r2);
                            if (cd2.length > 0) {
                                appendLog("OK! VAR retry success! Data: " + bytesToHex(cd2));
                                allData.append("VAR_DATA:").append(bytesToHex(cd2)).append("\n");
                                parseAndShowBalance(cd2);
                                gotData = true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                appendLog("  VAR exception: " + e.getMessage());
            }
        }

        lastRawData = allData.toString();
        tvRawData.setText(lastRawData.isEmpty() ? "(No data)" : lastRawData);
        tvStatus.setText(gotData ? "Read OK!" : "No data found");
    }

    private void tryNfcA(Tag tag) {
        NfcA nfcA = NfcA.get(tag);
        if (nfcA == null) {
            appendLog("NfcA not supported");
            tvStatus.setText("Unsupported card type");
            return;
        }
        try {
            nfcA.connect();
            appendLog("NfcA ATQA:" + bytesToHex(nfcA.getAtqa()) + " SAK:" + String.format("%02X", nfcA.getSak()));
            for (int block = 0; block <= 12; block++) {
                byte[] cmd = b(0x30, block);
                byte[] resp = nfcA.transceive(cmd);
                if (resp != null && resp.length >= 16) {
                    appendLog("Block" + block + ": " + bytesToHex(resp));
                    parseAndShowBalance(resp);
                }
            }
            nfcA.close();
        } catch (Exception e) {
            appendLog("NfcA failed: " + e.getMessage());
        }
    }

    private byte[] buildSelectApdu(byte[] aid) {
        byte[] apdu = new byte[6 + aid.length];
        apdu[0] = 0; apdu[1] = (byte)0xA4; apdu[2] = 4; apdu[3] = 0;
        apdu[4] = (byte) aid.length;
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        apdu[5 + aid.length] = 0;
        return apdu;
    }

    private int sw(byte[] resp) {
        if (resp == null || resp.length < 2) return -1;
        return (resp[resp.length-2] & 0xFF) * 256 + (resp[resp.length-1] & 0xFF);
    }

    private byte[] dataOf(byte[] resp) {
        if (resp == null || resp.length <= 2) return new byte[0];
        return Arrays.copyOf(resp, resp.length - 2);
    }

    private void logResp(String tag, byte[] cmd, byte[] resp) {
        String cmdHex = bytesToHex(cmd);
        String respHex = resp != null ? bytesToHex(resp) : "null";
        appendLog("  " + tag + " TX:" + cmdHex + " RX:" + respHex);
    }

    private boolean isAllZeros(byte[] data) {
        if (data == null) return true;
        for (byte bb : data) if (bb != 0) return false;
        return true;
    }

    private void parseAndShowBalance(byte[] data) {
        if (data == null || data.length < 4) return;
        appendLog("--- Try parse balance ---");
        appendLog("Raw: " + bytesToHex(data));
        // Try BCD-like format: bytes as BCD digits
        if (data.length >= 4) {
            long bal1 = ((data[0] & 0xFF) * 10000 + (data[1] & 0xFF) * 100 + (data[2] & 0xFF));
            tvBalance.setText("Balance: " + String.format("%.2f", bal1 / 100.0) + " yuan");
            appendLog("Balance: " + String.format("%.2f", bal1 / 100.0) + " yuan");
        }
        if (data.length >= 8) {
            int hi = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            int lo = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            appendLog("Format2 balance: " + String.format("%.2f", hi * 100 + lo) + " yuan");
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuilder.insert(0, "[" + ts + "] " + msg + "\n");
        if (logBuilder.length() > 4000) logBuilder.setLength(4000);
        if (tvLog != null) tvLog.setText(logBuilder.toString());
    }

    private void autoSaveDataToFile() {
        if (lastRawData == null || lastRawData.isEmpty()) return;
        saveDataToFile("[Auto]");
    }

    private void saveDataToFile() {
        saveDataToFile("[Manual]");
    }

    private void saveDataToFile(String prefix) {
        if (lastRawData == null || lastRawData.isEmpty()) {
            appendLog("(No data to save)");
            return;
        }
        String fn = "water_card_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        File dir;
        File externalDir = getExternalFilesDir(null);
        if (externalDir != null) {
            dir = new File(externalDir, "water_card_dump");
        } else {
            dir = new File(getFilesDir(), "water_card_dump");
        }
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                appendLog("mkdir failed, fallback to internal");
                dir = new File(getFilesDir(), "water_card_dump");
                dir.mkdirs();
            }
        }
        File f = new File(dir, fn);
        try (FileWriter fw = new FileWriter(f)) {
            fw.write("=== Water Card Data ===\n");
            fw.write("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            fw.write("UID: " + lastUid + "\n");
            fw.write("ATS: " + lastAts + "\n");
            fw.write("=== HEX Data ===\n");
            fw.write(lastRawData);
            appendLog(prefix + "Saved: " + f.getAbsolutePath());
        } catch (IOException e) {
            appendLog("Save failed: " + e.getMessage());
            Log.e(TAG, "Save file failed", e);
        }
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
