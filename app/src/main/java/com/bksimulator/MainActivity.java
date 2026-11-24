package com.bksimulator;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int PICK_ZIP = 101;
    private static final int SAVE_ZIP = 102;
    private static final int PICK_AUDIO = 103;
    private String pendingAudioKey = "";
    private WebView web;
    private File packageDir;
    private JSONObject currentConfig = new JSONObject();
    private JSONObject pendingExport;
    private MediaPlayer player;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        packageDir = new File(getFilesDir(), "active-bk");
        packageDir.mkdirs();
        currentConfig = defaultConfig();
        web = new WebView(this);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "BK");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private JSONObject defaultConfig() {
        JSONObject root = new JSONObject();
        JSONObject events = new JSONObject();
        try {
            root.put("formatVersion", 1);
            root.put("id", "my-bk");
            root.put("name", "Мой бортовой компьютер");
            root.put("author", "");
            events.put("stop", new JSONObject().put("name", "Остановка").put("file", ""));
            events.put("nextStation", new JSONObject().put("name", "Следующая станция").put("file", ""));
            root.put("events", events);
        } catch (Exception ignored) {}
        return root;
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }
    private void sendConfig(JSONObject config) {
        currentConfig = config;
        String encoded = JSONObject.quote(config.toString());
        runOnUiThread(() -> web.evaluateJavascript("window.onBkImported && window.onBkImported(" + encoded + ")", null));
    }

    public class Bridge {
        @JavascriptInterface public void importZip() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                startActivityForResult(i, PICK_ZIP);
            });
        }
        @JavascriptInterface public void exportZip(String json) {
            try {
                pendingExport = new JSONObject(json);
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, safeName(pendingExport.optString("id", "my-bk")) + ".zip");
                    startActivityForResult(i, SAVE_ZIP);
                });
            } catch (Exception e) { toast("Не удалось подготовить ZIP: " + e.getMessage()); }
        }
        @JavascriptInterface public void pickAudio(String eventKey) {
            pendingAudioKey = eventKey;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                startActivityForResult(i, PICK_AUDIO);
            });
        }
        @JavascriptInterface public void play(String filename) {
            runOnUiThread(() -> playAudio(filename));
        }
        @JavascriptInterface public void notifyMessage(String message) { toast(message); }
    }

    private String safeName(String s) { return s.replaceAll("[^a-zA-Z0-9_-]", "_"); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == PICK_ZIP) importArchive(data.getData());
            if (requestCode == SAVE_ZIP) writeArchive(data.getData());
            if (requestCode == PICK_AUDIO) importAudio(data.getData());
        } catch (Exception e) { toast("Ошибка: " + e.getMessage()); }
    }

    private void importAudio(android.net.Uri uri) throws Exception {
        String display = "audio_" + System.currentTimeMillis() + ".bin";
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            String mime = getContentResolver().getType(uri);
            String ext = "mp3";
            if (mime != null && mime.contains("wav")) ext = "wav";
            else if (mime != null && (mime.contains("ogg") || mime.contains("opus"))) ext = "ogg";
            else if (mime != null && mime.contains("mp4")) ext = "m4a";
            display = "audio_" + System.currentTimeMillis() + "." + ext;
            File target = new File(packageDir, display);
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] b = new byte[8192]; int n; long total = 0;
                while ((n = in.read(b)) > 0) { total += n; if (total > 50L*1024*1024) throw new IOException("Аудиофайл больше 50 МБ"); out.write(b,0,n); }
            }
        }
        String key = pendingAudioKey;
        String file = display;
        runOnUiThread(() -> web.evaluateJavascript("window.onAudioPicked && window.onAudioPicked(" + JSONObject.quote(key) + "," + JSONObject.quote(file) + ")", null));
        toast("Аудиофайл добавлен: " + display);
    }

    private void importArchive(android.net.Uri uri) throws Exception {
        File fresh = new File(getFilesDir(), "bk-import-" + System.currentTimeMillis());
        if (!fresh.mkdirs()) throw new IOException("Не удалось создать папку импорта");
        long total = 0;
        boolean foundJson = false;
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) throw new IOException("Небезопасный путь в ZIP");
                if (entry.isDirectory()) continue;
                File out = new File(fresh, name);
                if (!out.getCanonicalPath().startsWith(fresh.getCanonicalPath() + File.separator)) throw new IOException("Недопустимый путь");
                File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > 100L * 1024 * 1024) throw new IOException("Архив больше 100 МБ");
                        fos.write(buffer, 0, n);
                    }
                }
                if (name.equals("bk.json") || name.endsWith("/bk.json")) foundJson = true;
            }
        }
        File jsonFile = new File(fresh, "bk.json");
        if (!jsonFile.exists()) {
            File[] children = fresh.listFiles();
            if (children != null) for (File child : children) {
                File nested = new File(child, "bk.json");
                if (nested.exists()) { fresh = child; jsonFile = nested; break; }
            }
        }
        if (!jsonFile.exists()) throw new IOException("В архиве не найден bk.json");
        byte[] bytes = readAll(new FileInputStream(jsonFile), 2 * 1024 * 1024);
        JSONObject config = new JSONObject(new String(bytes, "UTF-8"));
        if (config.optInt("formatVersion", 0) != 1 || !(config.opt("events") instanceof JSONObject))
            throw new IOException("Неподдерживаемый формат bk.json (нужен formatVersion: 1 и events)");
        File old = packageDir;
        packageDir = new File(getFilesDir(), "active-bk");
        deleteTree(packageDir);
        copyTree(fresh, packageDir);
        deleteTree(fresh);
        sendConfig(config);
        toast("БК «" + config.optString("name", "Без названия") + "» импортирован");
    }

    private byte[] readAll(InputStream in, int max) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) { if (out.size() + n > max) throw new IOException("bk.json слишком большой"); out.write(b, 0, n); }
            return out.toByteArray();
        } finally { in.close(); }
    }
    private void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) { to.mkdirs(); File[] list = from.listFiles(); if (list != null) for (File f : list) copyTree(f, new File(to, f.getName())); }
        else { to.getParentFile().mkdirs(); try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { byte[] b = new byte[8192]; int n; while ((n=in.read(b))>0) out.write(b,0,n); } }
    }
    private void deleteTree(File f) { if (f.isDirectory()) { File[] a=f.listFiles(); if(a!=null) for(File c:a) deleteTree(c); } f.delete(); }

    private void writeArchive(android.net.Uri uri) throws Exception {
        JSONObject config = pendingExport == null ? currentConfig : pendingExport;
        try (OutputStream raw = getContentResolver().openOutputStream(uri);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            addBytes(zip, "bk.json", config.toString(2).getBytes("UTF-8"));
            JSONObject events = config.optJSONObject("events");
            if (events != null) {
                JSONArray names = events.names();
                if (names != null) for (int i=0; i<names.length(); i++) {
                    JSONObject event = events.optJSONObject(names.getString(i));
                    if (event == null) continue;
                    String file = event.optString("file", "");
                    if (file.isEmpty() || file.contains("..") || file.startsWith("/")) continue;
                    File audio = new File(packageDir, file);
                    if (audio.isFile() && audio.getCanonicalPath().startsWith(packageDir.getCanonicalPath() + File.separator))
                        addFile(zip, file, audio);
                }
            }
        }
        toast("ZIP-файл сохранён");
    }
    private void addBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private void addFile(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) { byte[] b=new byte[8192]; int n; while((n=in.read(b))>0) zip.write(b,0,n); }
        zip.closeEntry();
    }

    private void playAudio(String filename) {
        try {
            if (filename == null || filename.isEmpty() || filename.contains("..") || filename.startsWith("/")) { toast("Для этого события звук не назначен"); return; }
            File f = new File(packageDir, filename);
            if (!f.getCanonicalPath().startsWith(packageDir.getCanonicalPath() + File.separator) || !f.isFile()) { toast("Аудиофайл не найден: " + filename); return; }
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener((mp, what, extra) -> { toast("Не удалось воспроизвести аудио"); return true; });
            player.prepareAsync();
        } catch (Exception e) { toast("Ошибка аудио: " + e.getMessage()); }
    }
    @Override protected void onDestroy() { if (player != null) player.release(); if (web != null) web.destroy(); super.onDestroy(); }
}
