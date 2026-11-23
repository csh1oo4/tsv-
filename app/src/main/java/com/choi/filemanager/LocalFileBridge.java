package com.choi.filemanager;

import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The phone's own storage behind window.LocalFileBridge.
 *
 * A WebView has no file system access, so listing, copying, moving, renaming and deleting
 * on the phone all happen here. Android 11+ gates the whole shared storage behind
 * MANAGE_EXTERNAL_STORAGE ("모든 파일 접근"), so status() reports whether it is granted and
 * requestAllFiles() opens that one settings screen; the media permissions are requested at
 * the same time for Android 13+.
 *
 * Every path is resolved through allowed(File) first, so a malformed request from the page
 * can never touch /data or another app's storage.
 */
public final class LocalFileBridge {

    private static final String TAG = "NasFileManager";

    private final MainActivity activity;

    public LocalFileBridge(MainActivity activity) {
        this.activity = activity;
    }

    public void call(final String reqId, final String action, final String paramsJson) {
        activity.runIo(() -> {
            boolean ok = true;
            JSONObject payload;
            try {
                JSONObject params = parse(paramsJson);
                switch (action == null ? "" : action) {
                    case "status":
                        payload = status();
                        break;
                    case "list":
                        payload = list(params);
                        break;
                    case "mkdir":
                        payload = mkdir(params);
                        break;
                    case "rename":
                        payload = rename(params);
                        break;
                    case "delete":
                        payload = delete(params);
                        break;
                    case "copyMove":
                        payload = copyMove(params);
                        break;
                    case "details":
                        payload = details(params);
                        break;
                    case "storage":
                        payload = storage(params);
                        break;
                    case "open":
                        payload = open(params);
                        break;
                    case "share":
                        payload = share(params);
                        break;
                    case "scan":
                        payload = scan(params);
                        break;
                    case "trash":
                        payload = trash(params);
                        break;
                    default:
                        payload = error("알 수 없는 요청입니다: " + action);
                        ok = false;
                        break;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "local permission failure", e);
                payload = error("이 폴더에 접근할 권한이 없습니다. 설정에서 '모든 파일 접근'을 허용해 주세요.");
                ok = false;
            } catch (Exception e) {
                Log.w(TAG, "local action failed: " + action, e);
                payload = error("작업을 마치지 못했습니다. " + (e.getMessage() == null ? "" : e.getMessage()));
                ok = false;
            }
            activity.postResult(reqId, ok, payload.toString());
        });
    }

    private static JSONObject parse(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("message", message);
        } catch (Exception ignored) {
            // static keys
        }
        return out;
    }

    private static JSONObject ok() {
        JSONObject out = new JSONObject();
        try {
            out.put("success", true);
        } catch (Exception ignored) {
            // static key
        }
        return out;
    }

    // ------------------------------------------------------------------ status

    private JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            boolean allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
            out.put("success", true);
            out.put("sdk", Build.VERSION.SDK_INT);
            out.put("allFiles", allFiles);
            out.put("serverBase", activity.serverBase());
            out.put("serverToken", activity.serverToken());
            out.put("videoCacheDir", activity.videoCacheDir());
            out.put("openCacheDir", new File(activity.getCacheDir(), "open").getAbsolutePath());
            out.put("roots", roots());
            JSONObject storage = storageFor(Environment.getExternalStorageDirectory());
            out.put("storage", storage);
        } catch (Exception ignored) {
            // static keys
        }
        return out;
    }

    /** Storage roots: the built-in storage, any SD card, and the usual quick folders. */
    private JSONArray roots() {
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.isDirectory()) {
            addRoot(out, seen, "내부 저장소", internal.getAbsolutePath(), "phone");
        }
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            Arrays.sort(volumes, Comparator.comparing(File::getName));
            for (File volume : volumes) {
                String name = volume.getName();
                if (!volume.isDirectory() || "emulated".equals(name) || "self".equals(name)) {
                    continue;
                }
                if (!volume.canRead()) {
                    continue;
                }
                addRoot(out, seen, "SD카드 (" + name + ")", volume.getAbsolutePath(), "sd");
            }
        }
        return out;
    }

    private static void addRoot(JSONArray out, List<String> seen, String label, String path,
                                String kind) {
        if (seen.contains(path)) {
            return;
        }
        seen.add(path);
        try {
            JSONObject item = new JSONObject();
            item.put("label", label);
            item.put("path", path);
            item.put("kind", kind);
            out.put(item);
        } catch (Exception ignored) {
            // static keys
        }
    }

    /** The quick-access shelf under the 내 폰 tab. */
    private JSONArray quickFolders() {
        JSONArray out = new JSONArray();
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[][] candidates = {
                {"DCIM/Camera", "카메라"},
                {"DCIM", "DCIM"},
                {"Pictures", "사진"},
                {"Download", "다운로드"},
                {"Movies", "동영상"},
                {"Music", "음악"},
                {"Documents", "문서"},
                {"Screenshots", "스크린샷"},
                {"Pictures/Screenshots", "스크린샷"},
                {"Android/media", "앱 미디어"},
        };
        for (String[] candidate : candidates) {
            File dir = new File(base, candidate[0]);
            if (!dir.isDirectory()) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("path", dir.getAbsolutePath());
                item.put("label", candidate[1]);
                out.put(item);
            } catch (Exception ignored) {
                // static keys
            }
        }
        return out;
    }

    private JSONObject storageFor(File path) {
        JSONObject out = new JSONObject();
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            out.put("total", total);
            out.put("free", free);
            out.put("used", Math.max(0, total - free));
        } catch (Exception ignored) {
            // storage stats are informational
        }
        return out;
    }

    // ------------------------------------------------------------------- list

    private JSONObject list(JSONObject params) throws Exception {
        String path = Core.normalizePath(params.optString("path", "/"));
        boolean showHidden = params.optBoolean("showHidden", false);
        File dir = allowed(new File(path));
        if (!dir.isDirectory()) {
            throw new SecurityException("not a directory");
        }
        File[] children = dir.listFiles();
        if (children == null) {
            throw new SecurityException("cannot list");
        }
        List<File> files = new ArrayList<>();
        for (File child : children) {
            if (!showHidden && Core.isHiddenName(child.getName())) {
                continue;
            }
            files.add(child);
        }
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        JSONArray entries = new JSONArray();
        for (File child : files) {
            JSONObject entry = new JSONObject();
            String name = child.getName();
            boolean isDir = child.isDirectory();
            entry.put("name", name);
            entry.put("path", child.getAbsolutePath());
            entry.put("isDir", isDir);
            entry.put("size", isDir ? 0 : child.length());
            entry.put("mtime", child.lastModified());
            entry.put("kind", isDir ? "folder" : Core.kindOf(name));
            entry.put("mime", Core.mimeOf(name));
            entry.put("writable", child.canWrite());
            entries.put(entry);
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("path", dir.getAbsolutePath());
        out.put("parent", parentOrSelf(dir));
        out.put("entries", entries);
        out.put("quick", quickFolders());
        out.put("storage", storageFor(dir));
        return out;
    }

    private static String parentOrSelf(File dir) {
        File parent = dir.getParentFile();
        String path = dir.getAbsolutePath();
        if (parent == null) {
            return path;
        }
        // Do not walk above the storage root.
        if (!parent.getAbsolutePath().startsWith("/storage")) {
            return path;
        }
        return parent.getAbsolutePath();
    }

    // -------------------------------------------------------------- mutations

    private JSONObject mkdir(JSONObject params) throws Exception {
        File target = allowed(new File(params.optString("path", "")));
        if (target.exists()) {
            throw new SecurityException("already exists");
        }
        if (!target.mkdirs()) {
            throw new SecurityException("mkdir failed");
        }
        JSONObject out = ok();
        out.put("path", target.getAbsolutePath());
        return out;
    }

    private JSONObject rename(JSONObject params) throws Exception {
        File source = allowed(new File(params.optString("path", "")));
        String newName = Core.sanitizeName(params.optString("newName", ""));
        if (newName.isEmpty()) {
            throw new SecurityException("empty name");
        }
        File parent = source.getParentFile();
        if (parent == null) {
            throw new SecurityException("no parent");
        }
        File target = new File(parent, newName);
        if (target.exists()) {
            throw new SecurityException("already exists");
        }
        if (!source.renameTo(target)) {
            throw new SecurityException("rename failed");
        }
        JSONObject out = ok();
        out.put("path", target.getAbsolutePath());
        return out;
    }

    private JSONObject delete(JSONObject params) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        if (paths == null || paths.length() == 0) {
            return ok();
        }
        int deleted = 0;
        for (int i = 0; i < paths.length(); i++) {
            File target = allowed(new File(paths.optString(i)));
            if (isStorageRoot(target)) {
                throw new SecurityException("refusing to delete a storage root");
            }
            if (deleteRecursive(target)) {
                deleted++;
            }
        }
        JSONObject out = ok();
        out.put("count", deleted);
        return out;
    }

    private boolean deleteRecursive(File file) {
        if (!file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        boolean done = file.delete();
        if (done && !Core.isHiddenName(file.getName())) {
            activity.scanMedia(file);
        }
        return done;
    }

    private JSONObject copyMove(JSONObject params) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        boolean move = params.optBoolean("move", false);
        boolean overwrite = params.optBoolean("overwrite", false);
        File destDir = allowed(new File(params.optString("destDir", "")));
        if (paths == null || paths.length() == 0) {
            return ok();
        }
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new SecurityException("no destination");
        }
        int copied = 0;
        JSONArray failed = new JSONArray();
        for (int i = 0; i < paths.length(); i++) {
            File source = allowed(new File(paths.optString(i)));
            File target = new File(destDir, source.getName());
            if (target.getAbsolutePath().equals(source.getAbsolutePath())) {
                continue;
            }
            if (target.exists()) {
                if (!overwrite) {
                    target = new File(destDir,
                            Core.uniqueName(source.getName(), namesIn(destDir)));
                } else if (target.isDirectory()) {
                    deleteRecursive(target);
                }
            }
            boolean done;
            if (move) {
                done = source.renameTo(target);
                if (!done) {
                    // Different volume (SD card): copy then delete.
                    done = copyRecursive(source, target);
                    if (done) {
                        deleteRecursive(source);
                    }
                }
            } else {
                done = copyRecursive(source, target);
            }
            if (done) {
                copied++;
                activity.scanMedia(target);
            } else {
                failed.put(source.getName());
            }
        }
        JSONObject out = ok();
        out.put("count", copied);
        out.put("failed", failed);
        return out;
    }

    private static java.util.Set<String> namesIn(File dir) {
        java.util.Set<String> names = new java.util.HashSet<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                names.add(child.getName());
            }
        }
        return names;
    }

    private boolean copyRecursive(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                return false;
            }
            File[] children = source.listFiles();
            if (children == null) {
                return false;
            }
            boolean all = true;
            for (File child : children) {
                all &= copyRecursive(child, new File(target, child.getName()));
            }
            return all;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "copy failed: " + source, e);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private JSONObject details(JSONObject params) throws Exception {
        File target = allowed(new File(params.optString("path", "")));
        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("path", target.getAbsolutePath());
        out.put("name", target.getName());
        out.put("isDir", target.isDirectory());
        out.put("mtime", target.lastModified());
        out.put("writable", target.canWrite());
        if (target.isDirectory()) {
            long[] stats = folderStats(target, 0);
            out.put("size", stats[0]);
            out.put("count", stats[1]);
        } else {
            out.put("size", target.length());
            out.put("count", 1);
        }
        return out;
    }

    /** {bytes, entries} for a folder, with a depth guard. */
    private long[] folderStats(File dir, int depth) {
        long bytes = 0;
        long count = 0;
        File[] children = depth > 12 ? null : dir.listFiles();
        if (children == null) {
            return new long[]{0, 0};
        }
        for (File child : children) {
            if (child.isDirectory()) {
                long[] sub = folderStats(child, depth + 1);
                bytes += sub[0];
                count += sub[1];
            } else {
                bytes += child.length();
                count++;
            }
        }
        return new long[]{bytes, count};
    }

    private JSONObject storage(JSONObject params) {
        String path = params.optString("path", Environment.getExternalStorageDirectory().getAbsolutePath());
        JSONObject out = ok();
        try {
            out.put("storage", storageFor(new File(path)));
        } catch (Exception ignored) {
            // informational
        }
        return out;
    }

    // --------------------------------------------------------------- external

    private JSONObject open(JSONObject params) throws Exception {
        File file = allowed(new File(params.optString("path", "")));
        if (!file.isFile()) {
            throw new SecurityException("not a file");
        }
        activity.openWithExternalApp(file, Core.mimeOf(file.getName()));
        return ok();
    }

    private JSONObject share(JSONObject params) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        if (paths == null || paths.length() == 0) {
            return ok();
        }
        ArrayList<File> files = new ArrayList<>();
        for (int i = 0; i < paths.length(); i++) {
            files.add(allowed(new File(paths.optString(i))));
        }
        activity.shareFiles(files);
        return ok();
    }

    private JSONObject scan(JSONObject params) {
        try {
            activity.scanMedia(new File(params.optString("path", "")));
        } catch (Exception ignored) {
            // best effort
        }
        return ok();
    }

    /** Moves a file into the app's own trash folder so 삭제 is undoable for a minute. */
    private JSONObject trash(JSONObject params) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        File trashDir = new File(activity.getCacheDir(), "trash");
        if (!trashDir.exists() && !trashDir.mkdirs()) {
            throw new SecurityException("no trash");
        }
        if (paths == null) {
            return ok();
        }
        for (int i = 0; i < paths.length(); i++) {
            File source = allowed(new File(paths.optString(i)));
            File target = new File(trashDir, System.currentTimeMillis() + "_" + source.getName());
            if (!source.renameTo(target)) {
                copyRecursive(source, target);
                deleteRecursive(source);
            }
        }
        return ok();
    }

    // ------------------------------------------------------------------ guards

    private File allowed(File file) throws Exception {
        if (file == null) {
            throw new SecurityException("no path");
        }
        String path = file.getAbsolutePath();
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new SecurityException("relative path");
        }
        String canonical = file.getCanonicalPath();
        if (canonical.startsWith("/data/") || canonical.startsWith("/system/")
                || canonical.startsWith("/proc/") || canonical.startsWith("/sys/")) {
            throw new SecurityException("not shared storage");
        }
        boolean shared = canonical.startsWith("/storage/")
                || canonical.startsWith("/sdcard")
                || canonical.startsWith(activity.getFilesDir().getAbsolutePath())
                || canonical.startsWith(activity.getCacheDir().getAbsolutePath())
                || canonical.startsWith(activity.getExternalFilesDir(null) == null
                ? "/nonexistent" : activity.getExternalFilesDir(null).getAbsolutePath())
                || canonical.startsWith(activity.getExternalCacheDir() == null
                ? "/nonexistent" : activity.getExternalCacheDir().getAbsolutePath());
        if (!shared) {
            throw new SecurityException("outside shared storage");
        }
        return file;
    }

    private static boolean isStorageRoot(File file) {
        String path = file.getAbsolutePath();
        return "/storage/emulated/0".equals(path) || "/sdcard".equals(path)
                || "/storage".equals(path) || "/".equals(path)
                || (path.startsWith("/storage/") && path.indexOf('/', "/storage/".length()) < 0);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }
}
