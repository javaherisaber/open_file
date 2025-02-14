package com.crazecoder.openfile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import com.crazecoder.openfile.utils.JsonUtil;
import com.crazecoder.openfile.utils.MapUtil;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class OpenFilePlugin implements MethodCallHandler
        , FlutterPlugin
        , ActivityAware
        , PluginRegistry.RequestPermissionsResultListener
        , PluginRegistry.ActivityResultListener {

    private @Nullable FlutterPluginBinding flutterPluginBinding;
    private Context context;
    private Activity activity;
    private MethodChannel channel;
    private Result result;
    private String filePath;
    private String typeString;
    private boolean isResultSubmitted = false;

    private static final int REQUEST_CODE = 33432;
    private static final int RESULT_CODE = 0x12;
    private static final String TYPE_STRING_APK = "application/vnd.android.package-archive";

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        this.flutterPluginBinding = binding;
        context = flutterPluginBinding.getApplicationContext();
        setup();
    }

    private void setup() {
        if (flutterPluginBinding != null) {
            channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "open_file");
            channel.setMethodCallHandler(this);
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        this.flutterPluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }
        activity = null;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) == PermissionChecker.PERMISSION_GRANTED;
    }

    private void requestPermission(String permission) {
        ActivityCompat.requestPermissions(activity, new String[]{permission}, REQUEST_CODE);
    }

    @Override
    @SuppressLint("NewApi")
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        isResultSubmitted = false;
        if (call.method.equals("open_file")) {
            this.result = result;
            filePath = call.argument("file_path");
            if (call.hasArgument("type") && call.argument("type") != null) {
                typeString = call.argument("type");
            } else {
                typeString = getFileType(filePath);
            }
            if (pathRequiresPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if(!isFileAvailable()){
                        return;
                    }
                    if (!isMediaStorePath() && !Environment.isExternalStorageManager()) {
                        result(-3, "Permission denied: android.Manifest.permission.MANAGE_EXTERNAL_STORAGE");
                        return;
                    }
                }
                if (canStartActivityWithPermission()) {
                    startActivity();
                } else if (Build.VERSION.SDK_INT < 33) {
                    requestPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
                } else if (typeString.startsWith("image")) {
                    requestPermission(Manifest.permission.READ_MEDIA_IMAGES);
                } else if (typeString.startsWith("video")) {
                    requestPermission(Manifest.permission.READ_MEDIA_VIDEO);
                } else if (typeString.startsWith("audio")) {
                    requestPermission(Manifest.permission.READ_MEDIA_AUDIO);
                }
            } else {
                startActivity();
            }
        } else {
            result.notImplemented();
            isResultSubmitted = true;
        }
    }

    private boolean canStartActivityWithPermission() {
        return (Build.VERSION.SDK_INT < 33 && hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) ||
                (Build.VERSION.SDK_INT >= 33 && typeString.startsWith("image") && hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) ||
                (Build.VERSION.SDK_INT >= 33 && typeString.startsWith("video") && hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) ||
                (Build.VERSION.SDK_INT >= 33 && typeString.startsWith("audio") && hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) ||
                (Build.VERSION.SDK_INT >= 33 && !(typeString.startsWith("image") || typeString.startsWith("video") || typeString.startsWith("audio")));
    }

    private boolean isMediaStorePath(){
        boolean isMediaStorePath = false;
        String[] mediaStorePath = {"/DCIM/"
                ,"/Pictures/"
                ,"/Movies/"
                ,"/Alarms/"
                ,"/Audiobooks/"
                ,"/Music/"
                ,"/Notifications/"
                ,"/Podcasts/"
                ,"/Ringtones/"
                ,"/Download/"};
        for (String s : mediaStorePath) {
            if (filePath.contains(s)) {
                isMediaStorePath = true;
                break;
            }
        }
        return isMediaStorePath;
    }

    private boolean pathRequiresPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        try {
            String appDirCanonicalPath = new File(context.getApplicationInfo().dataDir).getCanonicalPath();
            String fileCanonicalPath = new File(filePath).getCanonicalPath();
            String extCanonicalPath = context.getExternalFilesDir(null).getCanonicalPath();
            return !(fileCanonicalPath.startsWith(appDirCanonicalPath) || fileCanonicalPath.startsWith(extCanonicalPath));
        } catch (IOException e) {
            e.printStackTrace();
            return true;
        }
    }

    private boolean isFileAvailable(){
        if (filePath == null) {
            result(-4, "the file path cannot be null");
            return false;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            result(-2, "the " + filePath + " file does not exists");
            return false;
        }
        return true;
    }

    private void startActivity() {
        if(!isFileAvailable()){
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (TYPE_STRING_APK.equals(typeString))
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        else
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String packageName = context.getPackageName();
            Uri uri = FileProvider.getUriForFile(context, packageName + ".fileProvider.com.crazecoder.openfile", new File(filePath));
            intent.setDataAndType(uri, typeString);
        } else {
            intent.setDataAndType(Uri.fromFile(new File(filePath)), typeString);
        }
        int type = 0;
        String message = "done";
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            type = -1;
            message = "No APP found to open this file。";
        } catch (Exception e) {
            type = -4;
            message = "File opened incorrectly。";
        }
        result(type, message);
    }

    private String getFileType(String filePath) {
        String[] fileStrs = filePath.split("\\.");
        String fileTypeStr = fileStrs[fileStrs.length - 1].toLowerCase();
        switch (fileTypeStr) {
            case "3gp":
                return "video/3gpp";
            case "torrent":
                return "application/x-bittorrent";
            case "kml":
                return "application/vnd.google-earth.kml+xml";
            case "gpx":
                return "application/gpx+xml";
            case "apk":
                return TYPE_STRING_APK;
            case "asf":
                return "video/x-ms-asf";
            case "avi":
                return "video/x-msvideo";
            case "bin":
            case "class":
            case "exe":
                return "application/octet-stream";
            case "bmp":
                return "image/bmp";
            case "c":
                return "text/plain";
            case "conf":
                return "text/plain";
            case "cpp":
                return "text/plain";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
            case "csv":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "gif":
                return "image/gif";
            case "gtar":
                return "application/x-gtar";
            case "gz":
                return "application/x-gzip";
            case "h":
                return "text/plain";
            case "htm":
                return "text/html";
            case "html":
                return "text/html";
            case "jar":
                return "application/java-archive";
            case "java":
                return "text/plain";
            case "jpeg":
                return "image/jpeg";
            case "jpg":
                return "image/jpeg";
            case "js":
                return "application/x-javascript";
            case "log":
                return "text/plain";
            case "m3u":
                return "audio/x-mpegurl";
            case "m4a":
                return "audio/mp4a-latm";
            case "m4b":
                return "audio/mp4a-latm";
            case "m4p":
                return "audio/mp4a-latm";
            case "m4u":
                return "video/vnd.mpegurl";
            case "m4v":
                return "video/x-m4v";
            case "mov":
                return "video/quicktime";
            case "mp2":
                return "audio/x-mpeg";
            case "mp3":
                return "audio/x-mpeg";
            case "mp4":
                return "video/mp4";
            case "mpc":
                return "application/vnd.mpohun.certificate";
            case "mpe":
                return "video/mpeg";
            case "mpeg":
                return "video/mpeg";
            case "mpg":
                return "video/mpeg";
            case "mpg4":
                return "video/mp4";
            case "mpga":
                return "audio/mpeg";
            case "msg":
                return "application/vnd.ms-outlook";
            case "ogg":
                return "audio/ogg";
            case "pdf":
                return "application/pdf";
            case "png":
                return "image/png";
            case "pps":
                return "application/vnd.ms-powerpoint";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "prop":
                return "text/plain";
            case "rc":
                return "text/plain";
            case "rmvb":
                return "audio/x-pn-realaudio";
            case "rtf":
                return "application/rtf";
            case "sh":
                return "text/plain";
            case "tar":
                return "application/x-tar";
            case "tgz":
                return "application/x-compressed";
            case "txt":
                return "text/plain";
            case "wav":
                return "audio/x-wav";
            case "wma":
                return "audio/x-ms-wma";
            case "wmv":
                return "audio/x-ms-wmv";
            case "wps":
                return "application/vnd.ms-works";
            case "xml":
                return "text/plain";
            case "z":
                return "application/x-compress";
            case "zip":
                return "application/x-zip-compressed";
            default:
                return "*/*";
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return false;
        for (String string : strings) {
            if (!hasPermission(string)) {
                result(-3, "Permission denied: " + string);
                return false;
            }
        }
        startActivity();
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == RESULT_CODE) {
            startActivity();
        }
        return false;
    }

    private void result(int type, String message) {
        if (result != null && !isResultSubmitted) {
            Map<String, Object> map = MapUtil.createMap(type, message);
            result.success(JsonUtil.toJson(map));
            isResultSubmitted = true;
        }
    }
}