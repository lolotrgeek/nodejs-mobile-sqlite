/*
  Node.js for Mobile Apps Cordova plugin.
  Implements the plugin APIs exposed to the Cordova layer and routes messages
  between the Cordova layer and the Node.js engine.
 */

package com.notify;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.system.Os;
import android.system.ErrnoException;

import java.io.*;
import java.lang.System;
import java.util.*;
import java.util.concurrent.Semaphore;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class NodeJS extends Service {

    private static Context context = null;
    private static AssetManager assetManager = null;
    private static NodeJS instance;

    private static String filesDir;
    private static final String PROJECT_ROOT = "nodejs-project";
    private static final String BUILTIN_ASSETS = "nodejs-project";
    private static final String BUILTIN_MODULES = "nodejs-project";
    private static final String TRASH_DIR = "nodejs-project-trash";
    private static final String BUILTIN_NATIVE_ASSETS_PREFIX = "nodejs-native-assets-";
    private static String nodeAppRootAbsolutePath = "";
    private static String nodePath = "";
    private static String trashDir = "";
    private static String nativeAssetsPath = "";

    private static final String SHARED_PREFS = "NODEJS_MOBILE_PREFS";
    private static final String LAST_UPDATED_TIME = "NODEJS_MOBILE_APK_LastUpdateTime";
    private long lastUpdateTime = 1;
    private long previousLastUpdateTime = 0;

    private static Semaphore initSemaphore = new Semaphore(1);
    private static boolean initCompleted = false;
    private static IOException ioe = null;

    private static String LOGTAG = "NODEJS-BRIDGE";
    private static String SYSTEM_CHANNEL = "_SYSTEM_";
    private static String EVENT_CHANNEL = "_EVENTS_";

    private static boolean engineAlreadyStarted = false;

    private static final Object onlyOneEngineStartingAtATimeLock = new Object();

    // Flag to indicate if node is ready to receive app events.
    private static boolean nodeIsReadyForAppEvents = false;

    public IBinder onBind(Intent intent) {
        return null;
    }

    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    public native Integer startNodeWithArguments(String[] arguments, String nodePath, boolean redirectOutputToLogcat);

    public native void sendMessageToNodeChannel(String channelName, String msg);

    public native void registerNodeDataDirPath(String dataDir);

    public native String getCurrentABIName();

    public static NodeJS getInstance() {
        return instance;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onCreate() {
        instance = this;
        super.onCreate();
        Log.d(LOGTAG, "Node Service Initialize");

        context = getApplicationContext();
        assetManager = context.getAssets();

        // Sets the TMPDIR environment to the cacheDir, to be used in Node as os.tmpdir
        try {
            Os.setenv("TMPDIR", context.getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            e.printStackTrace();
        }
        filesDir = context.getFilesDir().getAbsolutePath();

        // Register the filesDir as the Node data dir.
        registerNodeDataDirPath(filesDir);

        nodeAppRootAbsolutePath = filesDir + "/" + PROJECT_ROOT;
        nodePath = nodeAppRootAbsolutePath + ":" + filesDir + "/" + BUILTIN_MODULES;
        trashDir = filesDir + "/" + TRASH_DIR;
        nativeAssetsPath = BUILTIN_NATIVE_ASSETS_PREFIX + getCurrentABIName();

        asyncInit();
    }

    private void asyncInit() {
        if (wasAPKUpdated()) {
            try {
                initSemaphore.acquire();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        emptyTrash();
                        try {
                            copyNodeJSAssets();
                            initCompleted = true;
                        } catch (IOException e) {
                            ioe = e;
                            Log.e(LOGTAG, "Node assets copy failed: " + e.toString());
                            e.printStackTrace();
                        }
                        initSemaphore.release();
                        emptyTrash();
                    }
                }).start();
            } catch (InterruptedException ie) {
                initSemaphore.release();
                ie.printStackTrace();
            }
        } else {
            initCompleted = true;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void systemMessageToNode() {
        if (nodeIsReadyForAppEvents) {
            Log.d(LOGTAG, "System msg to Node");
            sendMessageToNodeChannel(SYSTEM_CHANNEL, "alive");
        }
    }

    public void sendMessageToNode(String event, String data) {
        waitForInit();
        JSONObject message = new JSONObject();
        JSONArray payload = new JSONArray();
        try {
            payload.put(data);
            message.put("event", event);
            message.put("payload", payload.toString());
        } catch (JSONException e) {
            Log.e(LOGTAG, e.getMessage());
        }
        if (nodeIsReadyForAppEvents) {
            Log.i(LOGTAG, "Sending - " + message.toString());
            sendMessageToNodeChannel(EVENT_CHANNEL, message.toString());
        } else {
            Log.e(LOGTAG, "Unable to Send - " + message.toString());
        }

    }

    public static void sendMessageToApplication(String channelName, String msg) {
        if (channelName.equals(SYSTEM_CHANNEL)) {
            // If it's a system channel call, handle it in the plugin native side.
            handleAppChannelMessage(msg);
        } else {
            // Otherwise, send it.
            handleAppChannelMessage(msg);
        }
    }

    public static void handleAppChannelMessage(String msg) {
        instance.handleIncomingMessages(msg);
        if (msg.equals("ready-for-app-events")) {
            nodeIsReadyForAppEvents = true;
        }
    }

    public void handleIncomingMessages(String msg) {
    }

    public void startEngine(final String scriptFileName) {
        Log.d(LOGTAG, "StartEngine: " + scriptFileName);

        if (NodeJS.engineAlreadyStarted == true) {
            Log.i(LOGTAG, "Engine already started");
            return;
        }

        if (scriptFileName == null || scriptFileName.isEmpty()) {
            Log.i(LOGTAG, "Invalid filename");
            return;
        }

        final String scriptFileAbsolutePath = new String(NodeJS.nodeAppRootAbsolutePath + "/" + scriptFileName);
        Log.d(LOGTAG, "Script absolute path: " + scriptFileAbsolutePath);

        new Thread(new Runnable() {
            @Override
            public void run() {
                waitForInit();

                if (ioe != null) {
                    Log.i(LOGTAG, "Initialization failed: " + ioe.toString());
                    return;
                }

                synchronized (onlyOneEngineStartingAtATimeLock) {
                    if (NodeJS.engineAlreadyStarted == true) {
                        Log.i(LOGTAG, "Engine already started");
                        return;
                    }
                    File fileObject = new File(scriptFileAbsolutePath);
                    if (!fileObject.exists()) {
                        Log.i(LOGTAG, "File not found");
                        return;
                    }
                    NodeJS.engineAlreadyStarted = true;
                }

                Log.i(LOGTAG, "Engine Starting");

                startNodeWithArguments(new String[]{"node", scriptFileAbsolutePath},
                        NodeJS.nodePath, true);
            }
        }).start();
        nodeIsReadyForAppEvents = true;
    }

    public void startEngineWithScript(final String scriptBody) {
        Log.d(LOGTAG, "StartEngineWithScript: " + scriptBody);

        if (NodeJS.engineAlreadyStarted == true) {
            Log.i(LOGTAG, "Engine already started");
            return;
        }

        if (scriptBody == null || scriptBody.isEmpty()) {
            Log.i(LOGTAG, "Script is empty");
            return;
        }

        final boolean redirectOutputToLogcat = true;
        final String scriptBodyToRun = new String(scriptBody);

        new Thread(new Runnable() {
            @Override
            public void run() {
                waitForInit();

                if (ioe != null) {
                    Log.i(LOGTAG, "Initialization failed: " + ioe.toString());
                    return;
                }

                synchronized (onlyOneEngineStartingAtATimeLock) {
                    if (NodeJS.engineAlreadyStarted == true) {
                        Log.i(LOGTAG, "Engine already started");
                        return;
                    }
                    NodeJS.engineAlreadyStarted = true;
                }

                Log.i(LOGTAG, "Engine Started");

                startNodeWithArguments(
                        new String[]{"node", "-e", scriptBodyToRun},
                        NodeJS.nodePath,
                        redirectOutputToLogcat);
            }
        }).start();
    }

    /**
     * Private assets helpers
     */

    private void waitForInit() {
        if (!initCompleted) {
            try {
                initSemaphore.acquire();
                initSemaphore.release();
            } catch (InterruptedException ie) {
                initSemaphore.release();
                ie.printStackTrace();
            }
        }
    }

    private boolean wasAPKUpdated() {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE);
        this.previousLastUpdateTime = prefs.getLong(LAST_UPDATED_TIME, 0);

        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            this.lastUpdateTime = packageInfo.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return (this.lastUpdateTime != this.previousLastUpdateTime);
    }

    private void saveLastUpdateTime() {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(LAST_UPDATED_TIME, this.lastUpdateTime);
        editor.commit();
    }

    private void emptyTrash() {
        File trash = new File(NodeJS.trashDir);
        if (trash.exists()) {
            deleteFolderRecursively(trash);
        }
    }

    private void copyNativeAssets() throws IOException {
        // Load the additional asset folders and files lists
        ArrayList<String> nativeDirs = readFileFromAssets(nativeAssetsPath + "/dir.list");
        ArrayList<String> nativeFiles = readFileFromAssets(nativeAssetsPath + "/file.list");

        // Copy additional asset files to project working folder
        if (nativeFiles.size() > 0) {
            Log.d(LOGTAG, "Building folder hierarchy for " + nativeAssetsPath);
            for (String dir : nativeDirs) {
                new File(nodeAppRootAbsolutePath + "/" + dir).mkdirs();
            }
            Log.d(LOGTAG, "Copying assets using file list for " + nativeAssetsPath);
            for (String file : nativeFiles) {
                String src = nativeAssetsPath + "/" + file;
                String dest = nodeAppRootAbsolutePath + "/" + file;
                copyAssetFile(src, dest);
            }
        } else {
            Log.d(LOGTAG, "No assets to copy from " + nativeAssetsPath);
        }
    }

    private void copyNodeJSAssets() throws IOException {
        // Delete the existing plugin assets in the working folder
        File nodejsBuiltinModulesFolder = new File(NodeJS.filesDir + "/" + BUILTIN_ASSETS);
        if (nodejsBuiltinModulesFolder.exists()) {
            deleteFolderRecursively(nodejsBuiltinModulesFolder);
        }
        // Copy the plugin assets from the APK
        copyFolder(BUILTIN_ASSETS);

        // If present, move the existing node project root to the trash
        File nodejsProjectFolder = new File(NodeJS.filesDir + "/" + PROJECT_ROOT);
        if (nodejsProjectFolder.exists()) {
            Log.d(LOGTAG, "Moving existing project folder to trash");
            File trash = new File(NodeJS.trashDir);
            nodejsProjectFolder.renameTo(trash);
        }
        nodejsProjectFolder.mkdirs();

        // Load the nodejs project's folders and files lists
        ArrayList<String> dirs = readFileFromAssets("dir.list");
        ArrayList<String> files = readFileFromAssets("file.list");

        // Copy the node project files to the project working folder
        if (files.size() > 0) {
            Log.d(LOGTAG, "Copying node project assets using the files list");

            for (String dir : dirs) {
                new File(NodeJS.filesDir + "/" + dir).mkdirs();
            }

            for (String file : files) {
                String src = file;
                String dest = NodeJS.filesDir + "/" + file;
                NodeJS.copyAssetFile(src, dest);
            }
        } else {
            Log.d(LOGTAG, "Copying node project assets enumerating the APK assets folder");
            copyFolder(PROJECT_ROOT);
        }

        // Copy native modules assets
        copyNativeAssets();

        Log.d(LOGTAG, "Node assets copied");
        saveLastUpdateTime();
    }

    private ArrayList<String> readFileFromAssets(String filename) {
        ArrayList lines = new ArrayList();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(filename)));
            String line = reader.readLine();
            while (line != null) {
                lines.add(line);
                line = reader.readLine();
            }
            reader.close();
        } catch (FileNotFoundException e) {
            Log.d(LOGTAG, "File not found: " + filename);
        } catch (IOException e) {
            e.printStackTrace();
            lines = new ArrayList();
        }
        return lines;
    }

    private void copyFolder(String srcFolder) throws IOException {
        copyAssetFolder(srcFolder, NodeJS.filesDir + "/" + srcFolder);
    }

    // Adapted from https://stackoverflow.com/a/22903693
    private static void copyAssetFolder(String srcFolder, String destPath) throws IOException {
        String[] files = assetManager.list(srcFolder);
        if (files.length == 0) {
            // Copy the file
            copyAssetFile(srcFolder, destPath);
        } else {
            // Create the folder
            new File(destPath).mkdirs();
            for (String file : files) {
                copyAssetFolder(srcFolder + "/" + file, destPath + "/" + file);
            }
        }
    }

    private static void copyAssetFile(String srcFolder, String destPath) throws IOException {
        InputStream in = assetManager.open(srcFolder);
        new File(destPath).createNewFile();
        OutputStream out = new FileOutputStream(destPath);
        copyFile(in, out);
        in.close();
        in = null;
        out.flush();
        out.close();
        out = null;
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void deleteFolderRecursively(File file) {
        try {
            for (File childFile : file.listFiles()) {
                if (childFile.isDirectory()) {
                    deleteFolderRecursively(childFile);
                } else {
                    childFile.delete();
                }
            }
            file.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean getOptionRedirectOutputToLogcat(final JSONObject startOptions) {
        if (BuildConfig.DEBUG) {
            if (startOptions.names() != null) {
                for (int i = 0; i < startOptions.names().length(); i++) {
                    try {
                        Log.d(LOGTAG, "Start engine option: " + startOptions.names().getString(i));
                    } catch (JSONException e) {
                    }
                }
            }
        }

        final String OPTION_NAME = "redirectOutputToLogcat";
        boolean result = true;
        if (startOptions.has(OPTION_NAME) == true) {
            try {
                result = startOptions.getBoolean(OPTION_NAME);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return result;
    }
}