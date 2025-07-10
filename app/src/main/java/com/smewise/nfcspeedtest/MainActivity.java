package com.smewise.nfcspeedtest;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private TextView tvResults;
    private TextView tvNfcStatus;
    private EditText etWriteData;
    private Button btnWrite;
    private boolean writeMode = false;
    private String dataToWrite = "";
    private TextView tvTechList;
    private Spinner spinnerTechType;
    private String selectedTechType = "NDEF"; // 默認NDEF

    // 固定測試用參數
    private byte serviceCode1 = 0x0B; // Felica 常見可寫入 service code（請依標籤支援調整）
    private byte blockNumber = 0x00;  // 要寫入的 block 編號
    //private byte[] dataToWriteNfcF = new byte[]{ /* NFC-F data */ };
    //private byte[] dataToWriteNfcV = new byte[]{ /* NFC-B data */ };
    private byte[] dataToWriteNfcF = new byte[]{
            0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F, 0x10
    };
    private byte[] dataToWriteNfcV = new byte[]{ 0x01, 0x02, 0x03, 0x04 }; // 每個區塊 4 字節

    private static final String TAG = "NFCTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(Color.GRAY);

        // 初始化UI元件
        tvResults = findViewById(R.id.tvResults);
        tvNfcStatus = findViewById(R.id.tvNfcStatus);
        etWriteData = findViewById(R.id.etWriteData);
        btnWrite = findViewById(R.id.btnWrite);
        tvTechList = findViewById(R.id.tvTechList);
        spinnerTechType = findViewById(R.id.spinnerTechType);
        tvResults.setMovementMethod(new ScrollingMovementMethod()); // 讓 TextView 可以滾動
        spinnerTechType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] techTypes = getResources().getStringArray(R.array.nfc_tech_types);
                selectedTechType = techTypes[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedTechType = "NDEF";
            }
        });

        etWriteData.setText("0102030405060708090A0B0C0D0E0F10");

        // 檢查設備是否支持NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            tvNfcStatus.setText("NFC狀態: 此設備不支持NFC");
            Log.w(TAG,"NFC狀態: 此設備不支持NFC");
            btnWrite.setEnabled(false);
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            tvNfcStatus.setText("NFC狀態: NFC未啟用");
        } else {
            tvNfcStatus.setText("NFC狀態: 已就緒，請靠近NFC標籤");
        }

        // 設置PendingIntent以便在檢測到NFC標籤時啟動此Activity
        pendingIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);

        // 寫入按鈕點擊事件
        btnWrite.setOnClickListener(v -> {
            dataToWrite = etWriteData.getText().toString();
            if (dataToWrite.isEmpty()) {
                Toast.makeText(MainActivity.this, "請輸入要寫入的數據", Toast.LENGTH_SHORT).show();
                return;
            }
            writeMode = true;
            tvNfcStatus.setText("NFC狀態: 準備寫入數據，請靠近NFC標籤");
            Toast.makeText(MainActivity.this, "重新靠卡以寫入數據", Toast.LENGTH_SHORT).show();

        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            // 啟用前台調度以處理NFC標籤
            IntentFilter tagDetected = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
            IntentFilter[] filters = new IntentFilter[]{tagDetected};
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, null);
            tvNfcStatus.setText("NFC狀態: 已就緒，請靠近NFC標籤");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            detectTagTechnologies(tag);  // 新增：檢測所有支援的技術
            if (writeMode) {
                // 寫入模式
                writeTag(tag);
            } else {
                // 讀取模式
                readTag(tag);
                long startTime = System.currentTimeMillis();
                readMifareClassicBlock(tag, 0, 1, System.currentTimeMillis()); // 假設要讀取 區段 0 中的第 1 區塊（也就是 Block 01）

            }
        }
    }

    // 新增方法：檢測標籤支援的所有技術
    private void detectTagTechnologies(Tag tag) {
        StringBuilder techs = new StringBuilder("支援的技術:\n");
        String[] techList = tag.getTechList();

        for (String tech : techList) {
            techs.append("- ").append(tech.substring(tech.lastIndexOf('.') + 1)).append("\n");
        }

        tvTechList.setText(techs.toString());
    }

    private void readTag(Tag tag) {
        long startTime = System.currentTimeMillis();

        // 顯示標籤基本信息
        appendResult("檢測到標籤\nUID: " + bytesToHex(tag.getId()));

        // 嘗試用多種技術讀取
        try {
            if (Arrays.asList(tag.getTechList()).contains(Ndef.class.getName())) {
                readNdefTag(tag, startTime);
            }
            if (Arrays.asList(tag.getTechList()).contains(NfcA.class.getName())) {
                readNfcATag(tag, startTime);
            }
            if (Arrays.asList(tag.getTechList()).contains(NfcB.class.getName())) {
                readNfcBTag(tag, startTime);
            }
            if (Arrays.asList(tag.getTechList()).contains(NfcF.class.getName())) {
                readNfcFTag(tag, startTime);
            }
            if (Arrays.asList(tag.getTechList()).contains(NfcV.class.getName())) {
                readNfcVTag(tag, startTime);
            }
        } catch (Exception e) {
            appendResult("讀取過程中發生錯誤: " + e.getMessage());
            Log.w(TAG,"讀取過程中發生錯誤: ");
        }
    }


    // 在背景執行緒中執行讀取任務，並自動捕獲異常、更新 UI，如果發生錯誤，要顯示的錯誤訊息前綴
    private void startReadThread(Runnable task, String errorMessage) {
        new Thread(() -> {
            try {
                task.run(); // 執行讀取任務（例如 readNfcATag）
            } catch (Exception e) {
                // 捕獲異常，並在主線程更新 UI 顯示錯誤訊息
                runOnUiThread(() -> appendResult(errorMessage + ": " + e.getMessage()));
            }
        }).start();
    }

    // NDEF 標籤讀取 (原有方法改進)
    private void readNdefTag(Tag tag, long startTime) {
        Ndef ndef = Ndef.get(tag);
        try {
            ndef.connect();
            NdefMessage ndefMessage = ndef.getNdefMessage();
            long duration = System.currentTimeMillis() - startTime;

            if (ndefMessage != null) {
                NdefRecord[] records = ndefMessage.getRecords();
                for (NdefRecord record : records) {
                    if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN &&
                            Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {

                        try {
                            byte[] payload = record.getPayload();

                            // 解析文字資料
                            String textEncoding = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
                            int langCodeLen = payload[0] & 0x3F;
                            String text = new String(payload, 1 + langCodeLen, payload.length - 1 - langCodeLen, textEncoding);

                            appendResult("NDEF 讀取成功 - 耗時: " + duration + "ms\n內容: " + text);

                        } catch (Exception e) {
                            appendResult("NDEF 資料解析錯誤: " + e.getMessage());
                            Log.w(TAG,"NDEF 資料解析錯誤: ");
                        }
                    } else {
                        appendResult("NDEF 記錄不是文字格式");
                        Log.w(TAG,"NDEF 記錄不是文字格式");
                    }
                }
            }
        } catch (Exception e) {
            appendResult("NDEF讀取錯誤: " + e.getMessage());
        } finally {
            try { ndef.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // 新增：NFC-A (ISO 14443-3A) 標籤讀取
    private void readNfcATag(Tag tag, long startTime) {
        // 先判斷是否支援 IsoDep
        if (Arrays.asList(tag.getTechList()).contains(IsoDep.class.getName())) {
            readEasyCardWithIsoDep(tag, startTime);
            return;
        }

        // 不支援 IsoDep 才使用 NfcA
        NfcA nfca = NfcA.get(tag);
        try {
            nfca.connect();
            byte[] atqa = nfca.getAtqa();
            byte[] sak = new byte[]{(byte)nfca.getSak()};
            byte[] uid = tag.getId();
            long duration = System.currentTimeMillis() - startTime;

            appendResult("NFC-A 讀取成功 - 耗時: " + duration + "ms\n" +
                    "UID: " + bytesToHex(uid) + "\n" +
                    "ATQA: " + bytesToHex(atqa) + "\n" +
                    "SAK: " + bytesToHex(sak) + "\n");

            // 檢查是否支援 IsoDep（如悠遊卡等金融票證）
            String[] techList = tag.getTechList();
            for (String tech : techList) {
                Log.d("NFC_TAG", "支援技術: " + tech);
            }

        } catch (IOException e) {
            appendResult("NFC-A 讀取錯誤: " + e.getMessage());
        } finally {
            try { nfca.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // 多組金鑰輪試
    private static final byte[][] COMMON_KEYS = {
            MifareClassic.KEY_DEFAULT, // FF FF FF FF FF FF
            {(byte) 0xA0, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xA4, (byte) 0xA5},
            {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00},
            {(byte) 0xD3, (byte) 0xF7, (byte) 0xD3, (byte) 0xF7, (byte) 0xD3, (byte) 0xF7} // 一些悠遊卡/一卡通可能用這組
    };
    private boolean authenticateWithKnownKeys(MifareClassic mifare, int sectorIndex) throws IOException {
        for (byte[] key : COMMON_KEYS) {
            if (mifare.authenticateSectorWithKeyA(sectorIndex, key)) {
                return true;
            }
            if (mifare.authenticateSectorWithKeyB(sectorIndex, key)) {
                return true;
            }
        }
        return false;
    }

    // 讀取特定區塊區段 呼叫方式:
    /*
    @Override
    protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    if (tag != null) {
        long startTime = System.currentTimeMillis();
        readMifareClassicBlock(tag, 0, 1, System.currentTimeMillis()); // 假設要讀取 區段 0 中的第 1 區塊（也就是 Block 01）
        }
    }
     */
    private void readMifareClassicBlock(Tag tag, int sectorIndex, int blockInSector, long startTime) {
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            //appendResult("此標籤不支援 MIFARE Classic");
            runOnUiThread(() -> appendResult("此標籤不支援 MIFARE Classic"));
            return;
        }

        try {
            mifare.connect();

            // 嘗試多組 Key A / Key B 認證
            boolean auth = false;
            byte[] usedKey = null;
            String usedKeyType = "";
            for (byte[] key : COMMON_KEYS) {
                if (mifare.authenticateSectorWithKeyA(sectorIndex, key)) {
                    auth = true;
                    usedKey = key;
                    usedKeyType = "Key A";
                    break;
                } else if (mifare.authenticateSectorWithKeyB(sectorIndex, key)) {
                    auth = true;
                    usedKey = key;
                    usedKeyType = "Key B";
                    break;
                }
            }

            if (!auth) {
                appendResult("區段 " + sectorIndex + " 認證失敗（未匹配任何金鑰）");
                return;
            }

            // 計算區塊 index（全域編號）
            int blockIndex = mifare.sectorToBlock(sectorIndex) + blockInSector;

            // 讀取區塊資料
            byte[] data = mifare.readBlock(blockIndex);
            long duration = System.currentTimeMillis() - startTime;

            /*appendResult(String.format(
                    "MIFARE Classic 區塊讀取成功\n耗時: %dms\n區段: %02d 區塊: %02d (全域 %02d)\n" +
                            "使用金鑰: %s (%s)\n資料: %s",
                    duration, sectorIndex, blockInSector, blockIndex,
                    bytesToHex(usedKey), usedKeyType, bytesToHex(data)
            ));*/

            // 改為不顯示金鑰
            appendResult(String.format(
                    "MIFARE Classic 區塊讀取成功\n耗時: %dms\n區段: %02d 區塊: %02d (全域 %02d)\n" +
                            "資料: %s",
                    duration, sectorIndex, blockInSector, blockIndex,
                    bytesToHex(data)
            ));

        } catch (IOException e) {
            //appendResult("讀取錯誤: " + e.getMessage());
            runOnUiThread(() -> appendResult("讀取錯誤: " + e.getMessage()));
        } finally {
            try {
                mifare.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 讀取全部區塊區段 呼叫方式:
    /*
    @Override
    protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    if (tag != null) {
        long startTime = System.currentTimeMillis();
        allReadMifareClassicBlock(tag, startTime);
        }
    }
     */
    private void allReadMifareClassicBlock(Tag tag, long startTime) {
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null) {
            appendResult("此標籤不支援 MIFARE Classic");
            return;
        }

        try {
            mifare.connect();
            int sectorCount = mifare.getSectorCount();
            long duration = System.currentTimeMillis() - startTime;

            StringBuilder result = new StringBuilder();
            result.append("MIFARE Classic 讀取成功 - 耗時: ").append(duration).append("ms\n");
            result.append("共有 ").append(sectorCount).append(" 個區段\n");

            for (int sector = 0; sector < sectorCount; sector++) {
                boolean auth = mifare.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT);
                if (!auth) {
                    result.append("區段 ").append(sector).append(": 驗證失敗\n");
                    continue;
                }

                int blockCount = mifare.getBlockCountInSector(sector);
                int blockIndex = mifare.sectorToBlock(sector);

                for (int block = 0; block < blockCount; block++) {
                    byte[] data = mifare.readBlock(blockIndex);
                    result.append(String.format("區段 %02d 區塊 %02d: %s\n",
                            sector, block, bytesToHex(data)));
                    blockIndex++;
                }
            }

            appendResult(result.toString());

        } catch (IOException e) {
            appendResult("MIFARE 讀取錯誤: " + e.getMessage());
        } finally {
            try {
                mifare.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    // NFC-B (ISO 14443-3B) 標籤讀取
    private void readNfcBTag(Tag tag, long startTime) {
        NfcB nfcb = NfcB.get(tag);
        try {
            nfcb.connect();
            byte[] appData = nfcb.getApplicationData();
            byte[] protInfo = nfcb.getProtocolInfo();
            byte[] uid = tag.getId();
            long duration = System.currentTimeMillis() - startTime;

            appendResult("NFC-B 讀取成功 - 耗時: " + duration + "ms\n" +
                    "UID: " + bytesToHex(uid) + "\n" +
                    "應用數據: " + bytesToHex(appData) + "\n" +
                    "協議信息: " + bytesToHex(protInfo) + "\n");

        } catch (IOException e) {
            appendResult("NFC-B 讀取錯誤: " + e.getMessage());
        } finally {
            try { nfcb.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // NFCA  + IsoDep 讀取悠遊卡餘額範例
    private void readEasyCardWithIsoDep(Tag tag, long startTime) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            appendResult("不支援 IsoDep 進階通訊");
            return;
        }
        try {
            iso.connect();

            // 1. 選擇悠遊卡應用（用於羊城通的例子：PAY.TICL）
            byte[] selectCmd = hexStringToByteArray("00A40400085041592E5449434C00");
            byte[] selectResp = iso.transceive(selectCmd);
            if (!endsWith90OK(selectResp)) {
                appendResult("Select AID 失敗: " + bytesToHex(selectResp));
                return;
            }

            // 2. 讀取餘額（Response: Data + SW1 SW2，其中 SW1SW2 = 9000）
            byte[] balanceCmd = hexStringToByteArray("805C000204");
            byte[] balanceResp = iso.transceive(balanceCmd);
            if (!endsWith90OK(balanceResp)) {
                appendResult("讀取餘額失敗: " + bytesToHex(balanceResp));
                return;
            }

            // 擷取前面 N bytes 為餘額資料
            int balanceValue = ByteBuffer.wrap(balanceResp, 0, balanceResp.length - 2)
                    .getInt(); // 4 bytes 餘額
            long duration = System.currentTimeMillis() - startTime;

            appendResult(
                    "悠遊卡讀取成功 - 耗時: " + duration + "ms\n" +
                            "餘額: " + balanceValue / 100.0 + " 元"
            );

        } catch (IOException e) {
            appendResult("IsoDep 讀取失敗: " + e.getMessage());
        } finally {
            try { iso.close(); } catch (IOException ignored) {}
        }
    }

    // 新增：NFC-F (FeliCa) 標籤讀取
    private void readNfcFTag(Tag tag, long startTime) {
        // 先判斷是否支援 IsoDep
        if (Arrays.asList(tag.getTechList()).contains(IsoDep.class.getName())) {
            readEasyCardWithIsoDep(tag, startTime);
            return;
        }

        // 不支援 IsoDep 才使用 NfcA
        NfcF nfcf = NfcF.get(tag);
        try {
            nfcf.connect();
            byte[] id = nfcf.getManufacturer();
            byte[] systemCode = nfcf.getSystemCode();
            byte[] uid = tag.getId();
            long duration = System.currentTimeMillis() - startTime;

            appendResult("NFC-F 讀取成功 - 耗時: " + duration + "ms\n" +
                    "UID: " + bytesToHex(uid) + "\n" +
                    "製造商: " + bytesToHex(id) + "\n" +
                    "系統代碼: " + bytesToHex(systemCode) + "\n");

            // 檢查是否支援 IsoDep（如悠遊卡等金融票證）
            String[] techList = tag.getTechList();
            for (String tech : techList) {
                Log.d("NFC_TAG", "支援技術: " + tech);
            }

        } catch (IOException e) {
            appendResult("NFC-F 讀取錯誤: " + e.getMessage());
        } finally {
            try { nfcf.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // 新增：NFC-V (ISO 15693) 標籤讀取
    private void readNfcVTag(Tag tag, long startTime) {
        NfcV nfcv = NfcV.get(tag);
        try {
            nfcv.connect();

            byte[] dsfId = new byte[]{nfcv.getDsfId()}; // ✅ 修正這行
            byte[] respFlags = new byte[]{nfcv.getResponseFlags()};
            byte[] uid = tag.getId();
            long duration = System.currentTimeMillis() - startTime;

            appendResult("NFC-V 讀取成功 - 耗時: " + duration + "ms\n" +
                    "UID: " + bytesToHex(uid) + "\n" +
                    "DSFID: " + bytesToHex(dsfId) + "\n" +
                    "響應標誌: " + bytesToHex(respFlags) + "\n");
        } catch (IOException e) {
            appendResult("NFC-V 讀取錯誤: " + e.getMessage());
        } finally {
            try {
                nfcv.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 輔助方法：字節數組轉十六進制字符串
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // 輔助工具
    private boolean endsWith90OK(byte[] resp) {
        int len = resp.length;
        return len >= 2 && resp[len - 2] == (byte)0x90 && resp[len - 1] == (byte)0x00;
    }

    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len/2];
        for (int i=0; i < len; i+=2)
            data[i/2] = (byte)((Character.digit(hex.charAt(i),16) << 4)
                    + Character.digit(hex.charAt(i+1),16));
        return data;
    }

    // 設置寫入方法及錯誤處理
    private void writeTag(Tag tag) {
        long startTime = System.currentTimeMillis();

        try {
            // 根據選擇的技術類型調用對應寫入方法
            switch (selectedTechType) {
                case "NDEF":
                    writeNdefTag(tag, startTime);
                    break;
                case "NFC-A (MIFARE)":
                    writeNfcATag(tag, startTime);
                    break;
                case "NFC-B":
                    writeNfcBTag(tag, startTime);
                    break;
                case "NFC-F (FeliCa)":
                    writeNfcFTag(tag, serviceCode1,blockNumber,dataToWriteNfcF,startTime);
                    break;
                case "NFC-V":
                    writeNfcVTag(tag, blockNumber, dataToWriteNfcV, startTime);
                    break;
                default:
                    autoDetectAndWrite(tag, startTime);
            }
            writeMode = false; // 重置寫入模式
            tvNfcStatus.setText("NFC狀態: 寫入完成");
        } catch (IOException e) {
            appendResult("寫入錯誤: " + e.getMessage());
            Toast.makeText(this, "寫入失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (FormatException e) {
            appendResult("格式錯誤: " + e.getMessage());
            Toast.makeText(this, "格式錯誤: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            writeMode = false; // 確保寫入模式被重置
        }
    }


    // NDEF寫入 (原有方法改進)
    private void writeNdefTag(Tag tag, long startTime) throws IOException, FormatException {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) throw new IOException("標籤不支持NDEF格式");

        ndef.connect();
        try {
            if (!ndef.isWritable()) throw new IOException("標籤不可寫");

            NdefRecord record = NdefRecord.createTextRecord("en", dataToWrite);
            NdefMessage message = new NdefMessage(new NdefRecord[]{record});

            // 精確測量寫入時間
            long connectTime = System.currentTimeMillis() - startTime;
            long writeStart = System.currentTimeMillis();
            ndef.writeNdefMessage(message);
            long writeDuration = System.currentTimeMillis() - writeStart;
            long totalDuration = System.currentTimeMillis() - startTime;

            appendResult("NDEF寫入成功\n" +
                    "連接時間: " + connectTime + "ms\n" +
                    "純寫入時間: " + writeDuration + "ms\n" +
                    "總耗時: " + totalDuration + "ms\n" +
                    "數據: " + dataToWrite);
        } finally {
            ndef.close();
        }
    }


    // NFC-A寫入 (MIFARE Classic示例)
    private void writeNfcATag(Tag tag, long startTime) throws IOException {

        MifareClassic mifare = MifareClassic.get(tag);
        mifare.connect();

        int sector = 0;      // Area 00
        int blockInSector = 1; // Block 01 in Area 00
        int blockIndex = mifare.sectorToBlock(sector) + blockInSector; // ➜ blockIndex = 1
        // 驗證 Sector 0 的金鑰（通常是預設 keyA）
        boolean auth = mifare.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT);
        if (!auth) throw new IOException("身份驗證失敗");

        try {

            byte[] data = {
                    0x01, 0x02, 0x03, 0x04,
                    0x05, 0x06, 0x07, 0x08,
                    0x09, 0x0A, 0x0B, 0x0C,
                    0x0D, 0x0E, 0x0F, 0x10
            };

            long writeStart = System.currentTimeMillis();
            mifare.writeBlock(blockIndex, data);
            long writeDuration = System.currentTimeMillis() - writeStart;

            appendResult("MIFARE Classic 寫入成功\n" +
                    "寫入時間: " + writeDuration + "ms\n" +
                    "Block: " + blockIndex +
                    "\nArea: " + sector);
        } finally {
            mifare.close();
        }
    }

    // NFC-B寫入示例
    private void writeNfcBTag(Tag tag, long startTime) throws IOException {
        NfcB nfcb = NfcB.get(tag);
        if (nfcb == null) throw new IOException("不是NFC-B標籤");

        nfcb.connect();
        try {
            // ISO 14443-4示例指令
            byte[] cmd = {
                    (byte)0x00,  // PCB
                    (byte)0x01,  // CID
                    (byte)0x02, (byte)0x03, (byte)0x04 // 自定義數據
            };

            long writeStart = System.currentTimeMillis();
            byte[] response = nfcb.transceive(cmd);
            long duration = System.currentTimeMillis() - startTime;

            appendResult("NFC-B寫入成功\n總耗時: " + duration + "ms\n" +
                    "響應: " + bytesToHex(response));
        } finally {
            nfcb.close();
        }
    }

    // 新增：NFC-F (FeliCa) 標籤寫入
    /*
    這是一個範例寫入，目前沒有樣本標籤可以測試寫入
    1. serviceCode1 是目標服務的 service code，需要根據標籤支援的服務設定。
    2. dataToWriteNfcF 必須是 16 bytes（Felica block 大小）。
    3. 並非所有 Felica 標籤都允許寫入，也可能有加密保護，無法使用 Write Without Encryption。
    4. 若需要使用加密方式，需依標籤規格實作加密認證程序。
     */
    private void writeNfcFTag(Tag tag, byte serviceCode1, byte blockNumber, byte[] dataToWriteNfcF, long startTime) {
        NfcF nfcf = NfcF.get(tag);
        try {
            nfcf.connect();

            byte[] id = tag.getId();

            // 建構 Write Without Encryption 指令 (0x08)
            ByteArrayOutputStream cmd = new ByteArrayOutputStream();
            cmd.write(0); // placeholder for length
            cmd.write(0x08); // Write Without Encryption
            cmd.write(id); // IDm
            cmd.write(1); // Number of services
            cmd.write((byte)(serviceCode1 & 0xFF));
            cmd.write((byte)((serviceCode1 >> 8) & 0xFF)); // Little-endian

            cmd.write(1); // Number of blocks
            cmd.write(0x80); // Block List Element with 2-byte block number
            cmd.write(0x00); // Block number (first block, for example)

            cmd.write(dataToWriteNfcF); // 16 bytes of data to write

            byte[] command = cmd.toByteArray();
            command[0] = (byte) command.length; // set length

            long writeStart = System.currentTimeMillis();
            byte[] response = nfcf.transceive(command);
            long duration = System.currentTimeMillis() - startTime;
            long writeDuration = System.currentTimeMillis() - writeStart;

            appendResult("NFC-F 寫入成功 - 總耗時: " + duration + "ms，寫入耗時: " + writeDuration + "ms");

        } catch (IOException e) {
            appendResult("NFC-F 寫入錯誤: " + e.getMessage());
        } finally {
            try { nfcf.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // 新增：NFC-V (ISO 15693) 標籤寫入

    private void writeNfcVTag(Tag tag, int blockNumber, byte[] dataToWriteNfcV, long startTime) {
        NfcV nfcv = NfcV.get(tag);
        try {
            nfcv.connect();
            byte[] uid = tag.getId();

            if (dataToWriteNfcV.length != 4) {
                appendResult("NFC-V 寫入錯誤: 每個 block 只能寫入 4 bytes 資料");
                return;
            }

            // 構造 Write Single Block 命令（0x21）
            // 標準格式：[flags][command][UID (8 bytes)][block number][data (4 bytes)]
            ByteArrayOutputStream cmd = new ByteArrayOutputStream();
            cmd.write(0x22); // Flags: Addressed (0x20) + high data rate (0x02)
            cmd.write(0x21); // Command: Write Single Block (0x21)
            for (int i = uid.length - 1; i >= 0; i--) {
                cmd.write(uid[i]); // UID 是 little-endian，要反轉
            }
            cmd.write(blockNumber); // Block number
            cmd.write(dataToWriteNfcV); // 4 bytes of data

            byte[] command = cmd.toByteArray();

            long writeStart = System.currentTimeMillis();
            byte[] response = nfcv.transceive(command);
            long writeDuration = System.currentTimeMillis() - writeStart;
            long totalDuration = System.currentTimeMillis() - startTime;

            // 通常 response[0] == 0x00 代表成功
            if (response != null && response.length > 0 && response[0] == 0x00) {
                appendResult("NFC-V 寫入成功 - 總耗時: " + totalDuration + "ms，寫入耗時: " + writeDuration + "ms");
            } else {
                appendResult("NFC-V 寫入失敗 - 回應碼異常");
            }

        } catch (IOException e) {
            appendResult("NFC-V 寫入錯誤: " + e.getMessage());
        } finally {
            try {
                nfcv.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    // 自動檢測並選擇合適的寫入方法
    private void autoDetectAndWrite(Tag tag, long startTime) throws IOException, FormatException {
        String[] techList = tag.getTechList();

        if (Arrays.asList(techList).contains(Ndef.class.getName())) {
            writeNdefTag(tag, startTime);
        } else if (Arrays.asList(techList).contains(NfcA.class.getName())) {
            writeNfcATag(tag, startTime);
        } else if (Arrays.asList(techList).contains(NfcB.class.getName())) {
            writeNfcBTag(tag, startTime);
        } else if (Arrays.asList(techList).contains(NfcF.class.getName())) {
            writeNfcFTag(tag, serviceCode1, blockNumber, dataToWriteNfcF, startTime);
        } else if (Arrays.asList(techList).contains(NfcV.class.getName())) {
            writeNfcVTag(tag, blockNumber, dataToWriteNfcV, startTime);
        } else {
            throw new IOException("無法自動確定合適的寫入技術");
        }
    }

    private void appendResult(final String text) {
        // 確保UI更新在主線程執行
        new Handler(Looper.getMainLooper()).post(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String currentText = tvResults.getText().toString();
            String newText = timestamp + ": " + text + "\n" + currentText;
            tvResults.setText(newText);
        });
    }
}