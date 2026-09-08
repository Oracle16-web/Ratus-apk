package com.reflector.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import io.socket.client.IO;
import io.socket.client.Socket;

public class ReflectorService extends Service {

    private static final String TAG = "ReflectorAndroidAgent";
    private static final String CHANNEL_ID = "ReflectorAgentChannel";

    // Config - Replace with your live Render URL
    private static final String RENDER_URL = "https://reflector-api.onrender.com";
    private static final String CLIENT_ID = "ANDROID-MASTER-AGENT";
    private static final String CLIENT_NAME = "Android Agent (" + Build.MODEL + ")";
    private static final String DEVICE_PASSWORD = "YourCustomPasswordHere";

    private Socket mSocket;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Reflector Agent")
                .setContentText("Background service active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(1, notification);

        new Thread(() -> {
            registerDevice();
            initSocketConnection();
        }).start();
    }

    private void registerDevice() {
        try {
            URL url = new URL(RENDER_URL + "/api/auth/register");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);

            JSONObject payload = new JSONObject();
            payload.put("client_id", CLIENT_ID);
            payload.put("name", CLIENT_NAME);
            payload.put("password", DEVICE_PASSWORD);
            payload.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            payload.put("port", 9090);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            Log.d(TAG, "Registration status: " + conn.getResponseCode());
        } catch (Exception e) {
            Log.e(TAG, "Registration failed", e);
        }
    }

    private void initSocketConnection() {
        try {
            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket"};
            opts.reconnection = true;

            mSocket = IO.socket(URI.create(RENDER_URL), opts);

            mSocket.on(Socket.EVENT_CONNECT, args -> {
                Log.d(TAG, "Connected to Render WebSocket.");
                try {
                    JSONObject authPayload = new JSONObject();
                    authPayload.put("client_id", CLIENT_ID);
                    authPayload.put("password", DEVICE_PASSWORD);
                    mSocket.emit("auth_agent", authPayload);
                } catch (Exception e) {
                    Log.e(TAG, "Auth payload error", e);
                }
            });

            mSocket.on("execute_command", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    try {
                        JSONObject data = (JSONObject) args[0];
                        String command = data.optString("command", "");
                        String sessionId = data.optString("session_id", "");

                        Log.d(TAG, "Executing command: " + command);
                        String output = executeRemoteCommand(command);

                        JSONObject response = new JSONObject();
                        response.put("client_id", CLIENT_ID);
                        response.put("session_id", sessionId);
                        response.put("output", output);

                        mSocket.emit("command_output", response);
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling command", e);
                    }
                }
            });

            mSocket.connect();
        } catch (Exception e) {
            Log.e(TAG, "Socket setup error", e);
        }
    }

    // =================================================================
    // REMOTE COMMAND EXECUTOR ENGINE (ANDROID)
    // =================================================================
    private String executeRemoteCommand(String commandStr) {
        String rawCmd = commandStr.trim();
        if (rawCmd.isEmpty()) return "Empty command.";

        String[] tokens = rawCmd.split(" ", 3);
        String action = tokens[0].toLowerCase();

        try {
            // 1. SYSTEM METRICS & BATTERY
            if (action.equals("sys") || action.equals("sysinfo")) {
                long totalMem = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                long freeMem = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                return "=== ANDROID SYSTEM METRICS ===\n" +
                       "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                       "Android OS: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                       "CPU Arch: " + Build.SUPPORTED_ABIS[0] + "\n" +
                       "Heap RAM Usage: " + (totalMem - freeMem) + "MB / " + totalMem + "MB";

            } else if (action.equals("battery")) {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                return "Android Battery Level: " + level + "%";

            // 2. FILE SYSTEM MANIPULATION
            } else if (action.equals("ls") || action.equals("dir")) {
                String pathStr = tokens.length > 1 ? tokens[1] : Environment.getExternalStorageDirectory().getAbsolutePath();
                File dir = new File(pathStr);
                if (!dir.exists() || !dir.isDirectory()) {
                    return "Invalid directory: " + pathStr;
                }

                File[] files = dir.listFiles();
                if (files == null) return "Access denied or directory empty.";

                StringBuilder sb = new StringBuilder("Directory listing for " + dir.getAbsolutePath() + ":\n");
                for (File f : files) {
                    String type = f.isDirectory() ? "[DIR] " : "[FILE]";
                    sb.append(String.format("%-8s %-12d %s\n", type, f.length(), f.getName()));
                }
                return sb.toString();

            } else if (action.equals("cat") || action.equals("read")) {
                if (tokens.length < 2) return "Usage: cat <filepath>";
                File file = new File(tokens[1]);
                if (!file.exists()) return "File not found.";

                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int bytesRead = fis.read(buffer);
                fis.close();

                if (bytesRead <= 0) return "File empty.";
                return new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

            } else if (action.equals("rm") || action.equals("delete")) {
                if (tokens.length < 2) return "Usage: rm <filepath>";
                File file = new File(tokens[1]);
                if (file.delete()) {
                    return "Deleted successfully: " + tokens[1];
                } else {
                    return "Failed to delete file/folder.";
                }

            } else if (action.equals("download")) {
                if (tokens.length < 2) return "Usage: download <filepath>";
                File file = new File(tokens[1]);
                if (!file.exists()) return "File not found.";

                byte[] bytes = new byte[(int) file.length()];
                FileInputStream fis = new FileInputStream(file);
                fis.read(bytes);
                fis.close();

                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                return "FILE_DOWNLOAD:" + file.getName() + ":" + b64;

            } else if (action.equals("upload")) {
                if (tokens.length < 3) return "Usage: upload <filepath> <base64>";
                File file = new File(tokens[1]);
                byte[] data = Base64.decode(tokens[2], Base64.DEFAULT);

                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();

                return "File written to " + file.getAbsolutePath();

            // 3. FALLBACK SHELL EXECUTION (Sh)
            } else {
                Process process = Runtime.getRuntime().exec(rawCmd);
                java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A");
                return s.hasNext() ? s.next() : "Command executed with no output.";
            }

        } catch (Exception e) {
            return "Execution error: " + e.getMessage();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Reflector Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSocket != null) mSocket.disconnect();
    }
}