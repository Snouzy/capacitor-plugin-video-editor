package com.whiteguru.capacitor.plugin.videoeditor;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.webkit.MimeTypeMap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@CapacitorPlugin(
    name = "VideoEditor",
    permissions = { @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = VideoEditorPlugin.STORAGE) }
)
public class VideoEditorPlugin extends Plugin {

    // Permission alias constants
    static final String STORAGE = "storage";

    // Message constants
    private static final String PERMISSION_DENIED_ERROR_STORAGE = "User denied access to storage";

    @PluginMethod
    public void edit(PluginCall call) {
        editLitr(call);
    }

    public void editLitr(PluginCall call) {
        String path = call.getString("path");
        JSObject trim = call.getObject("trim", new JSObject());
        JSObject transcode = call.getObject("transcode", new JSObject());

        if (path == null) {
            call.reject("Input file path is required");
            return;
        }

        if (checkStoragePermissions(call)) {
            Uri inputUri = Uri.parse(path);
            File inputFile;

            // content:// URIs (renvoyées par le Photo Picker Android / SAF /
            // PHPicker) ne sont PAS des chemins filesystem — `new File(uri.getPath())`
            // donne un chemin bidon (ex: `/-1/2/content:/media/...`). On stream le
            // contenu vers le cacheDir pour obtenir un vrai fichier que MediaTransformer
            // peut consommer. Pas de copie pour les `file://` ou paths absolus —
            // ces cas (iOS port d'API, sources internes app) restent zero-cost.
            if (ContentResolver.SCHEME_CONTENT.equals(inputUri.getScheme())) {
                try {
                    inputFile = resolveContentUriToCacheFile(inputUri);
                } catch (IOException e) {
                    call.reject("Failed to resolve content URI: " + e.getMessage());
                    return;
                }
            } else {
                inputFile = new File(inputUri.getPath());
            }

            if (!inputFile.canRead()) {
                call.reject("Cannot read input file: " + inputFile.getAbsolutePath());
                return;
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date());
            String fileName = "VID_" + timeStamp + "_";
            File storageDir = getContext().getCacheDir();

            execute(() -> {
                try {
                    File outputFile = File.createTempFile(fileName, ".mp4", storageDir);

                    VideoEditorLitr implementation = new VideoEditorLitr();

                    TrimSettings trimSettings = new TrimSettings(trim.getInteger("startsAt", 0), trim.getInteger("endsAt", 0));

                    TranscodeSettings transcodeSettings = new TranscodeSettings(
                        transcode.getInteger("height", 0),
                        transcode.getInteger("width", 0),
                        transcode.getBoolean("keepAspectRatio", true),
                        transcode.getInteger("fps", 30)
                    );

                    TransformationListener videoTransformationListener = new TransformationListener() {
                        @Override
                        public void onStarted(@NonNull String id) {
                            Logger.debug("Transcode started");
                        }

                        @Override
                        public void onProgress(@NonNull String id, float progress) {
                            Logger.debug("Transcode running " + progress);

                            JSObject ret = new JSObject();
                            ret.put("progress", progress);

                            notifyListeners("transcodeProgress", ret);
                        }

                        @Override
                        public void onCompleted(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                            Logger.debug("Transcode completed");

                            JSObject ret = new JSObject();
                            ret.put("file", createMediaFile(outputFile));
                            call.resolve(ret);
                        }

                        @Override
                        public void onCancelled(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
                            Logger.debug("Transcode cancelled");

                            call.reject("Transcode canceled");
                        }

                        @Override
                        public void onError(
                            @NonNull String id,
                            @Nullable Throwable cause,
                            @Nullable List<TrackTransformationInfo> trackTransformationInfos
                        ) {
                            Logger.debug("Transcode error: " + (cause != null ? cause.getMessage() : ""));

                            call.reject("Transcode failed: " + (cause != null ? cause.getMessage() : ""));
                        }
                    };

                    implementation.edit(getContext(), inputFile, outputFile, trimSettings, transcodeSettings, videoTransformationListener);
                } catch (Exception e) {
                    call.reject(e.getMessage());
                }
            });
        }
    }

    @PluginMethod
    public void thumbnail(PluginCall call) {
        String path = call.getString("path");
        int atMs = call.getInt("at", 0);
        int width = call.getInt("width", 0);
        int height = call.getInt("height", 0);

        if (path == null) {
            call.reject("Input file path is required");
            return;
        }

        if (checkStoragePermissions(call)) {
            Uri inputUri = Uri.parse(path);

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date());
            String fileName = "TH_" + timeStamp + "_";
            File storageDir = getContext().getCacheDir();

            File outputFile = null;

            try {
                outputFile = File.createTempFile(fileName, ".jpg", storageDir);

                VideoEditorLitr implementation = new VideoEditorLitr();

                implementation.thumbnail(this.getContext(), inputUri, outputFile, atMs, width, height);
            } catch (Exception e) {
                call.reject(e.getMessage());
                return;
            }

            JSObject ret = new JSObject();
            ret.put("file", createMediaFile(outputFile));
            call.resolve(ret);
        }
    }

    /**
     * Stream the content of a content:// URI into a temporary file in the
     * app cache directory and return that File. Required because LiTr's
     * MediaTransformer ultimately needs a real filesystem path — content URIs
     * aren't filesystem paths on Android.
     *
     * Caller is responsible for cleanup of the cache file when no longer
     * needed (or just let the OS reclaim cacheDir over time).
     */
    private File resolveContentUriToCacheFile(Uri contentUri) throws IOException {
        ContentResolver resolver = getContext().getContentResolver();
        // Best-effort extension from the source MIME so MediaTransformer's
        // sniffing doesn't choke (it mostly cares about container, not the
        // extension, but we keep it sane for debugging).
        String mimeType = resolver.getType(contentUri);
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (ext == null || ext.isEmpty()) ext = "mp4";

        File cacheFile = new File(getContext().getCacheDir(), "ve_input_" + System.currentTimeMillis() + "." + ext);
        try (InputStream in = resolver.openInputStream(contentUri); OutputStream out = new FileOutputStream(cacheFile)) {
            if (in == null) {
                throw new IOException("Cannot open content URI: " + contentUri);
            }
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
        return cacheFile;
    }

    private boolean checkStoragePermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (getPermissionState(STORAGE) != PermissionState.GRANTED) {
                requestPermissionForAlias(STORAGE, call, "storagePermissionsCallback");
                return false;
            }
        }
        return true;
    }

    /**
     * Completes the plugin call after a storage permission request
     *
     * @param call the plugin call
     */
    @PermissionCallback
    private void storagePermissionsCallback(PluginCall call) {
        if (getPermissionState(STORAGE) != PermissionState.GRANTED) {
            Logger.debug(getLogTag(), "User denied photos permission: " + getPermissionState(STORAGE).toString());
            call.reject(PERMISSION_DENIED_ERROR_STORAGE);
            return;
        }

        switch (call.getMethodName()) {
            case "edit":
                edit(call);
                break;
            case "thumbnail":
                thumbnail(call);
                break;
        }
    }

    /**
     * Creates a JSObject that represents a File from the Uri
     *
     * @param file the File of the audio/image/video
     * @return a JSObject that represents a File
     */
    private JSObject createMediaFile(File file) {
        Context context = getBridge().getActivity().getApplicationContext();
        Uri uri = Uri.fromFile(file);
        String mimeType;

        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            mimeType = context.getContentResolver().getType(uri);
        } else {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()));
        }

        JSObject ret = new JSObject();

        ret.put("name", file.getName());
        ret.put("path", uri);
        ret.put("type", mimeType);
        ret.put("size", file.length());

        return ret;
    }
}
