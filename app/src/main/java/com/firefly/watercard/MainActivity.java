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
                isoDep.connect();
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

        appendLog("=== Try KNOWN CPU command ===");
        byte[] r0 = isoDep.transceive(KNOWN_CPU_CMD);
        logResp("CPU", KNOWN_CPU_CMD, r0);
        if (r0 != null && r0.length >= 2) {
            int sw = (r0[r0.length-2] & 0xFF) * 256 + (r0[r0.length-1] & 0xFF);
            if (sw == 0x4AF1 || sw == 0x9000) {
                byte[] d = dataOf(r0);
                if (d.length > 0) {
                    appendLog("OK! CPU cmd success! Data: " + bytesToHex(d));
                    allData.append("CPU_DATA:").append(bytesToHex(d)).append("\n");
                    parseAndShowBalance(d);
                    gotData = true;
                }
            }
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
                for (int off = 0; off < 256; off += 32) {
                    byte[] readCmd = b(0x00, (byte)0xB0,
                            (byte)(off >> 8), (byte)(off & 0xFF), 0x00);
                    byte[] rr = isoDep.transceive(readCmd);
                    byte[] rd = dataOf(rr);
                    int rsw = rr != null ? sw(rr) : -1;
                    if (rd.length > 0 && !isAllZeros(rd)) {
                        appendLog("    READ off=" + off + " SW=" + String.format("%04X", rsw)
                                + " DATA=" + bytesToHex(rd));
                        allData.append("S").append(String.format("%04X", fid))
                               .append("O").append(String.format("%02X", off))
                               .append(":").append(bytesToHex(rd)).append("\n");
                        parseAndShowBalance(rd);
                        gotData = true;
                    }
                }
                for (int sfi = 0; sfi <= 31; sfi++) {
                    byte[] sfiCmd = b(0x00, (byte)0xB0, 0x00,
                            (byte)((sfi << 3) | 0x04), 0x00);
                    try {
                        byte[] sr = isoDep.transceive(sfiCmd);
                        byte[] sd = dataOf(sr);
                        int ssw = sr != null ? sw(sr) : -1;
                        if (sd.length > 0 && !isAllZeros(sd)) {
                            appendLog("    SFI=" + sfi + " SW=" + String.format("%04X", ssw)
                                    + " DATA=" + bytesToHex(sd));
                            allData.append("SFI").append(String.format("%02X", sfi))
                                   .append(":").append(bytesToHex(sd)).append("\n");
                            parseAndShowBalance(sd);
                            gotData = true;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        appendLog("=== SELECT by AID ===");
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

        appendLog("=== CPU command variants ===");
        byte[][] balCmds = {
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x04, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x10, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x27, 0x02, (byte)0xBE, (byte)0x90, 0x00, 0x20, (byte)0xA3, 0x00, 0x00, 0x00, (byte)0xFD, 0x00, 0x00, 0x00, (byte)0xFA),
            b(0x80, 0x5C, 0x00, 0x01, 0x02),
            b(0x80, 0x5C, 0x00, 0x02, 0x02),
            b(0x00, (byte)0xCA, 0x01, 0x00, 0x00),
            b(0x80, (byte)byte)0x84, 0x00, 0x00, 0x00),
        };
        for (byte[] cmd : balCmds) {
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
                }
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
