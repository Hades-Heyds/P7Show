package com.show.p7;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import com.xlsyn.light.LightHandler;
import com.xlsyn.light.LightConstant;
import com.xlsyn.light.NativeUtils;
import com.xlsyn.light.bean.CrossTli;
import com.xlsyn.light.bean.LightInfoResult;
import com.xlsyn.light.bean.LightPhase;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 红绿灯倒计时 SDK 会话骨架（路径A）。
 *
 * 职责：
 * 1. 加载 com.xlsyn.light SDK（liblight.so）并初始化原生会话（nativeInit 使用反编译得到的正式 Key，
 *    deviceId 取自本机设备标识；激活材料（绑定账户的 device_id / 新 Key）到位后替换即可出真实读秒）；
 * 2. 定位适配：请求系统定位更新，把坐标以 nativeUpdateGeoPos 喂给 SDK（GCJ02，lon/lat 放大 1e7）；
 * 3. 每 500ms 轮询 nativeGetLightInfo()，解析最近路口的灯态/相位，经 window.__lightData 推送到前端卡片；
 * 4. 设置页扫码入口：scanToActivate() 占位（激活材料到位后接入微信扫码流程）。
 *
 * 数据契约（window.__lightData）：
 * {
 *   ready: bool,          // 会话已初始化
 *   activateNeeded: bool, // SDK 返回未激活（status=9），需扫码激活
 *   status: int,          // nativeGetLightInfo().status（0=成功；2=未初始化；9=未激活）
 *   ts: long,             // 推送时间戳
 *   lights: [             // 最近路口灯态（空数组 = 暂无路口数据）
 *     { type: int, dist: int, dir: int, state: int, remainSec: int, nextState: int }
 *   ]
 * }
 */
public class LightSdkController {

    private static final String TAG = "P7Light";

    /** 反编译正式 APK 得到的 SDK Key（骨架先用；激活材料到位后可替换） */
    private static final String SDK_KEY = "5Vx3mRUYJRIfoJKOZ8g6Ov4f";
    /** 坐标类型：0=GCJ02 语义见 LightConstant.COORD_TYPE_*；原 App gps_model 默认 1(WGS84)，此处用 GCJ02=0 更贴近国内定位 */
    private static final int COORD_TYPE = 0;

    private static final long POLL_INTERVAL_MS = 500L;

    // ===== 微信扫码绑定激活（light.mojoxing.com 真实接口，规格见 light_activate_spec.md） =====
    /** 服务端 host（已逆向验证可用） */
    private static final String SERVER_HOST = "https://light.mojoxing.com";
    /** 服务端 Accesskey（签名 salt 前缀 + 请求头） */
    private static final String ACCESS_KEY = "Ib403jye5W1MhcGUdCrj6xMI";
    /** 服务端版本白名单：必须 3.0.0，否则 412 版本错误 */
    private static final String APP_VERSION = "3.0.0";
    /** 渠道号（实测 ch=1008 可返回二维码） */
    private static final String CHANNEL = "1008";
    /** 登录状态轮询间隔（建议 2s） */
    private static final long LOGIN_POLL_INTERVAL_MS = 2000L;
    /** 登录状态轮询最大次数（约 60 秒） */
    private static final int LOGIN_POLL_MAX_TIMES = 30;

    private final Object qrLock = new Object();
    /** 接口1 返回的登录令牌（轮询/激活接口复用） */
    private volatile String qrState = null;
    /** 登录状态轮询进行中标记（幂等防重入） */
    private volatile boolean loginPolling = false;

    private final Context context;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateGeoPos(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private LocationManager locationManager;
    private Location lastLocation;
    private volatile boolean initialized = false;
    private volatile boolean running = false;
    private long deviceUid = 0L;

    /** 扫码激活回调（骨架占位：激活材料到位后接入真实扫码/账户绑定） */
    public interface ScanActivateCallback {
        void onScanResult(boolean ok, String message);
    }

    public LightSdkController(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /** 启动会话：初始化 SDK + 开始定位监听 + 启动轮询 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        initSdk();
        startLocation();
        handler.post(pollRunnable);
        Log.i(TAG, "LightSdkController started");
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopLocation();
        if (initialized) {
            try {
                LightHandler.getInstance().nativeDestroy();
            } catch (Throwable t) {
                Log.w(TAG, "nativeDestroy failed: " + t.getMessage());
            }
            initialized = false;
        }
        Log.i(TAG, "LightSdkController stopped");
    }

    /** 初始化原生会话（骨架：SDK Key + 本机 deviceId；真实激活材料到位后替换 deviceUid 来源） */
    private void initSdk() {
        try {
            deviceUid = computeDeviceUid();
            String logPath = context.getExternalCacheDir() != null
                    ? new java.io.File(context.getExternalCacheDir(), "lightlog").getAbsolutePath()
                    : context.getCacheDir().getAbsolutePath();
            String version = LightHandler.getInstance().nativeGetVersion();
            Log.i(TAG, "SDK version = " + version + ", uid = " + deviceUid);
            initialized = LightHandler.getInstance().nativeInit(SDK_KEY, deviceUid, COORD_TYPE, logPath);
            Log.i(TAG, "nativeInit result = " + initialized);
        } catch (Throwable t) {
            Log.e(TAG, "initSdk failed: " + t.getMessage(), t);
            initialized = false;
        }
    }

    /** 设备标识 -> 会话 UID（与原 App r0.f() 语义一致：优先服务端下发 device_id_service，否则本地稳定哈希） */
    private long computeDeviceUid() {
        try {
            SharedPreferences sp = context.getSharedPreferences("p7show_light", Context.MODE_PRIVATE);
            long serviceUid = sp.getLong("device_id_service", 0L);
            if (serviceUid != 0L) {
                return serviceUid;
            }
            String serviceStr = sp.getString("device_id_service", null);
            if (serviceStr != null && !serviceStr.isEmpty()) {
                try {
                    return Long.parseLong(serviceStr);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        String uidStr = NativeUtils.getDeviceUid(context);
        if (uidStr == null || uidStr.isEmpty()) {
            uidStr = "p7show_default";
        }
        long h = 1125899906842597L;
        for (int i = 0; i < uidStr.length(); i++) {
            h = 31L * h + uidStr.charAt(i);
        }
        return h;
    }

    /** 绑定成功后用服务端下发的 device_id 重新初始化 SDK（nativeDestroy + nativeInit），使激活状态生效 */
    private void reinitSdk(final long newUid) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (initialized) {
                        try {
                            LightHandler.getInstance().nativeDestroy();
                        } catch (Throwable t) {
                            Log.w(TAG, "reinit destroy failed: " + t.getMessage());
                        }
                        initialized = false;
                    }
                    deviceUid = newUid;
                    String logPath = context.getExternalCacheDir() != null
                            ? new java.io.File(context.getExternalCacheDir(), "lightlog").getAbsolutePath()
                            : context.getCacheDir().getAbsolutePath();
                    initialized = LightHandler.getInstance().nativeInit(SDK_KEY, newUid, COORD_TYPE, logPath);
                    Log.i(TAG, "reinitSdk uid=" + newUid + " ok=" + initialized);
                } catch (Throwable t) {
                    Log.e(TAG, "reinitSdk failed: " + t.getMessage(), t);
                    initialized = false;
                }
            }
        });
    }

    /** 定位适配：GPS + 网络双 provider 监听，拿到最新位置即喂给 SDK */
    private void startLocation() {
        try {
            boolean hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean hasNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (hasNet) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener);
            }
            if (hasGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            }
            // 先用缓存位置保底，避免轮询期无数据
            Location last = hasGps ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            if (last == null && hasNet) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
                lastLocation = last;
                updateGeoPos(last);
            }
        } catch (SecurityException se) {
            Log.w(TAG, "location permission missing: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "startLocation failed: " + t.getMessage());
        }
    }

    private void stopLocation() {
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Throwable ignored) {
        }
    }

    /** 把系统定位换算成 SDK 需要的参数并上报（lon/lat 放大 1e7；速度转 km/h；bearing/accuracy 原样） */
    private void updateGeoPos(Location location) {
        if (location == null) {
            return;
        }
        lastLocation = location;
        try {
            long time = location.getTime() <= 0 ? System.currentTimeMillis() : location.getTime();
            int lonE7 = (int) (location.getLongitude() * 10000000d);
            int latE7 = (int) (location.getLatitude() * 10000000d);
            int bearing = (int) location.getBearing();
            int speedKmh = (int) (location.getSpeed() * 3.6f);
            int accuracy = (int) location.getAccuracy();
            int latE3 = (int) (location.getLatitude() * 1000d);
            boolean ok = LightHandler.getInstance().nativeUpdateGeoPos(time, lonE7, latE7, bearing, speedKmh, accuracy, latE3);
            if (!ok) {
                Log.v(TAG, "nativeUpdateGeoPos returned false");
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateGeoPos failed: " + t.getMessage());
        }
    }

    /** 500ms 轮询：读灯态 -> 序列化 -> 推送到前端 window.__lightData */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                pushLightData();
            } catch (Throwable t) {
                Log.w(TAG, "poll failed: " + t.getMessage());
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void pushLightData() {
        LightInfoResult info = null;
        boolean activateNeeded = false;
        int status = 0;
        if (initialized) {
            try {
                info = LightHandler.getInstance().nativeGetLightInfo();
                if (info != null) {
                    status = info.status;
                    if (status == LightConstant.RET_DEVICE_NOT_ACTIVATED) {
                        activateNeeded = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "nativeGetLightInfo failed: " + t.getMessage());
                status = LightConstant.RET_NOT_INIT;
            }
        } else {
            status = LightConstant.RET_NOT_INIT;
        }

        StringBuilder lights = new StringBuilder();
        if (info != null && info.crossTlis != null && info.crossTlis.length > 0) {
            // 取距离最近的路口（dist 最小）
            CrossTli best = info.crossTlis[0];
            for (CrossTli c : info.crossTlis) {
                if (c.dist < best.dist) {
                    best = c;
                }
            }
            LightPhase p = pickPhase(best);
            if (p != null) {
                long now = System.currentTimeMillis();
                int remain = p.curEndTime > now ? (int) Math.ceil((p.curEndTime - now) / 1000d) : 0;
                lights.append(String.format(Locale.US,
                        "{\"type\":%d,\"dist\":%d,\"dir\":%d,\"state\":%d,\"remainSec\":%d,\"nextState\":%d}",
                        best.lightType, best.dist, p.flowDir, p.curState, remain, p.nextState));
            }
        }

        String js = "window.__lightData={ready:" + initialized
                + ",activateNeeded:" + activateNeeded
                + ",status:" + status
                + ",ts:" + System.currentTimeMillis()
                + ",lights:[" + lights + "]};";
        Log.d(TAG, "push: " + js);
        final WebView wv = webView;
        if (wv != null) {
            wv.post(new Runnable() {
                @Override
                public void run() {
                    wv.evaluateJavascript(js, null);
                }
            });
        }
    }

    /** 相位选取：优先直行（flowDir=2），否则取第一个 */
    private LightPhase pickPhase(CrossTli cross) {
        if (cross.phases == null || cross.phases.length == 0) {
            return null;
        }
        for (LightPhase p : cross.phases) {
            if (p.flowDir == LightConstant.FLOW_DIR_STRAIGHT) {
                return p;
            }
        }
        return cross.phases[0];
    }

    /** 设置页扫码激活入口（保留兼容：路径A旧占位，前端已改走 lightFetchQrCode） */
    public void scanToActivate(ScanActivateCallback callback) {
        Log.i(TAG, "scanToActivate called (legacy placeholder)");
        String msg = "请点击获取微信绑定二维码后扫码激活读秒。";
        if (callback != null) {
            callback.onScanResult(false, msg);
        }
    }

    // ====================================================================
    // 微信扫码绑定激活（实体化，不再占位）
    // 接口规格 light_activate_spec.md（服务端 light.mojoxing.com 已逆向验证）：
    //  - 所有 /vehicle 接口请求头必带 HttpMethod/Path/Date/Sign/Accesskey/AppVersion
    //  - Sign = SHA1(str + SHA1(str).ToUpper()).ToUpper()，str = 方法大写 + Path + Date(秒)
    //           + query(按 key 字典序拼接，值非空才参与) + salt(Accesskey + 本地日期 yyyy-MM-dd)
    //  - 接口1 GET /vehicle/api/login/qrcode -> data.state / data.image(base64 PNG 数据URL)
    //  - 接口2 GET /vehicle/api/login/state?state=... 每 2s 轮询，data.state=1 绑定成功
    //  - 接口3 GET /vehicle/v1/activity 绑定成功后确认激活
    // 数据契约（window.__lightQrData）：{state, imageDataUrl, bound, error, ts}
    // ====================================================================

    /** 接口1：获取微信登录二维码（后台线程；成功后自动开始 2s 轮询绑定状态） */
    public void fetchWechatQrCode() {
        synchronized (qrLock) {
            qrState = null;
        }
        final String deviceIdStr = String.valueOf(computeDeviceUid());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> query = new HashMap<>();
                    query.put("ch", CHANNEL);
                    query.put("device_id", deviceIdStr);
                    query.put("orgid", deviceIdStr);
                    query.put("os_version", String.valueOf(Build.VERSION.SDK_INT));
                    String body = httpGet("/vehicle/api/login/qrcode", query);
                    if (body == null) {
                        pushQrData(null, null, false, "二维码获取失败（网络异常）");
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    if (json.optInt("code", -1) != 0) {
                        pushQrData(null, null, false, "二维码获取失败：" + json.optString("msg", "code!=0"));
                        return;
                    }
                    JSONObject data = json.optJSONObject("data");
                    if (data == null) {
                        pushQrData(null, null, false, "二维码数据缺失");
                        return;
                    }
                    String state = data.optString("state", "");
                    String image = data.optString("image", "");
                    if (state.isEmpty() || image.isEmpty()) {
                        pushQrData(null, null, false, "二维码数据不完整");
                        return;
                    }
                    synchronized (qrLock) {
                        qrState = state;
                    }
                    pushQrData(state, image, false, null);
                    // 二维码展示成功后自动开始轮询登录状态（2s，最多 60s）
                    pollLoginState(state);
                } catch (Throwable t) {
                    Log.e(TAG, "fetchWechatQrCode failed: " + t.getMessage(), t);
                    pushQrData(null, null, false, "二维码获取失败：" + t.getMessage());
                }
            }
        }, "light-qrcode").start();
    }

    /** 接口2：轮询微信登录状态（state=1 绑定成功，持久化 Authorization 并触发激活确认） */
    public void pollLoginState(final String state) {
        if (loginPolling) {
            return;
        }
        loginPolling = true;
        final String deviceIdStr = String.valueOf(computeDeviceUid());
        final String deviceModel = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                + (Build.BRAND == null ? "" : Build.BRAND);
        final String size = r0ScreenSize();
        final String dpi = r0ScreenDpi();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int tried = 0;
                while (loginPolling && tried < LOGIN_POLL_MAX_TIMES) {
                    tried++;
                    try {
                        Map<String, String> query = new HashMap<>();
                        query.put("ch", CHANNEL);
                        query.put("device_id", deviceIdStr);
                        query.put("orgid", deviceIdStr);
                        query.put("os_version", String.valueOf(Build.VERSION.SDK_INT));
                        query.put("state", state);
                        query.put("device_model", deviceModel);
                        query.put("size", size);
                        query.put("dpi", dpi);
                        String body = httpGet("/vehicle/api/login/state", query);
                        if (body != null) {
                            JSONObject json = new JSONObject(body);
                            if (json.optInt("code", -1) == 0) {
                                JSONObject data = json.optJSONObject("data");
                                if (data != null) {
                                    int st = data.optInt("state", -1);
                                    if (st == 1) {
                                        // 绑定成功：持久化 Authorization、服务端下发的 device_id（原包 v0() 语义），推送 bound=true，触发激活确认
                                        String auth = data.optString("Authorization", "");
                                        String srvDeviceId = data.optString("device_id", "");
                                        try {
                                            SharedPreferences sp = context.getSharedPreferences("p7show_light", Context.MODE_PRIVATE);
                                            SharedPreferences.Editor ed = sp.edit();
                                            if (!auth.isEmpty()) {
                                                ed.putString("authorization", auth);
                                            }
                                            ed.putString("login_state", state);
                                            if (!srvDeviceId.isEmpty()) {
                                                try {
                                                    ed.putLong("device_id_service", Long.parseLong(srvDeviceId));
                                                } catch (NumberFormatException nfe) {
                                                    ed.putString("device_id_service", srvDeviceId);
                                                }
                                            }
                                            ed.apply();
                                        } catch (Throwable ignored) {
                                        }
                                        loginPolling = false;
                                        pushQrData("", null, true, null);
                                        // 用服务端下发的 device_id 重建 SDK 会话，激活状态才生效（原包登录成功后再初始化 SDK 同义）
                                        if (!srvDeviceId.isEmpty()) {
                                            try {
                                                long serviceUid = Long.parseLong(srvDeviceId);
                                                if (serviceUid != 0L && serviceUid != deviceUid) {
                                                    reinitSdk(serviceUid);
                                                }
                                            } catch (NumberFormatException ignored) {
                                            }
                                        }
                                        fetchActivate();
                                        return;
                                    } else if (st == 2) {
                                        loginPolling = false;
                                        pushQrData(null, null, false, "设备不匹配，请使用原设备扫码");
                                        return;
                                    }
                                    // st=0 继续等待扫码
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "pollLoginState failed: " + t.getMessage());
                    }
                    try {
                        Thread.sleep(LOGIN_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        loginPolling = false;
                        return;
                    }
                }
                loginPolling = false;
                if (tried >= LOGIN_POLL_MAX_TIMES) {
                    pushQrData(null, null, false, "扫码超时，请重试");
                }
            }
        }, "light-login-poll").start();
    }

    /** 接口3：绑定成功后确认设备激活（后台线程；激活 key 持久化供后续会话使用） */
    public void fetchActivate() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String deviceIdStr = String.valueOf(computeDeviceUid());
                    Map<String, String> query = new HashMap<>();
                    query.put("ch", CHANNEL);
                    query.put("device_id", deviceIdStr);
                    query.put("orgid", deviceIdStr);
                    query.put("os_version", String.valueOf(Build.VERSION.SDK_INT));
                    String state;
                    synchronized (qrLock) {
                        state = qrState;
                    }
                    if (state != null && !state.isEmpty()) {
                        query.put("state", state);
                    }
                    // id/key 传空串探测（值为空不参与签名，按规格允许）
                    query.put("id", "");
                    query.put("key", "");
                    String auth = "";
                    try {
                        SharedPreferences sp = context.getSharedPreferences("p7show_light", Context.MODE_PRIVATE);
                        auth = sp.getString("authorization", "");
                    } catch (Throwable ignored) {
                    }
                    String body = httpGet("/vehicle/v1/activity", query, auth);
                    if (body == null) {
                        Log.w(TAG, "fetchActivate: no response");
                        return;
                    }
                    Log.i(TAG, "fetchActivate resp=" + body);
                    JSONObject json = new JSONObject(body);
                    if (json.optInt("code", -1) == 0) {
                        JSONObject data = json.optJSONObject("data");
                        String key = data != null ? data.optString("key", "") : "";
                        if (!key.isEmpty()) {
                            try {
                                SharedPreferences sp = context.getSharedPreferences("p7show_light", Context.MODE_PRIVATE);
                                sp.edit().putString("activate_key", key).apply();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "fetchActivate failed: " + t.getMessage());
                }
            }
        }, "light-activate").start();
    }

    /** 推送二维码/绑定状态到前端 window.__lightQrData={state,imageDataUrl,bound,error,ts} */
    private void pushQrData(final String state, final String imageDataUrl, final boolean bound, final String error) {
        final WebView wv = webView;
        if (wv == null) {
            return;
        }
        final String js = "window.__lightQrData={state:" + jsStringSafe(state == null ? "" : state)
                + ",imageDataUrl:" + (imageDataUrl == null ? "null" : jsStringSafe(imageDataUrl))
                + ",bound:" + bound
                + ",error:" + (error == null ? "null" : jsStringSafe(error))
                + ",ts:" + System.currentTimeMillis() + "};";
        Log.i(TAG, "pushQrData bound=" + bound + " hasImg=" + (imageDataUrl != null));
        wv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    wv.evaluateJavascript(js, null);
                } catch (Exception e) {
                    /* 页面未就绪等场景忽略 */
                }
            }
        });
    }

    /** JS 字符串转义（反斜杠/引号/换行/回车/Tab/控制字符） */
    private static String jsStringSafe(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 生成签名请求头（HttpMethod/Path/Date/Sign/Accesskey/AppVersion）；query 按 key 字典序、值非空才参与 */
    private static Map<String, String> buildSignedHeaders(String method, String path, Map<String, String> query) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
        }
        StringBuilder qs = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                qs.append('&');
            }
            qs.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        String date = String.valueOf(System.currentTimeMillis() / 1000L);
        // salt = Accesskey + 当天日期 yyyy-MM-dd（中国本地时区）
        String salt = ACCESS_KEY + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        // str = HttpMethod(大写) + Path + Date(秒) + query + salt
        String str = method.toUpperCase(Locale.US) + path + date + qs.toString() + salt;
        // Sign = SHA1(str + SHA1(str).ToUpper()).ToUpper()
        String inner = sha1Hex(str).toUpperCase(Locale.US);
        String sign = sha1Hex(str + inner).toUpperCase(Locale.US);

        Map<String, String> headers = new HashMap<>();
        headers.put("HttpMethod", method.toUpperCase(Locale.US));
        headers.put("Path", path);
        headers.put("Date", date);
        headers.put("Sign", sign);
        headers.put("Accesskey", ACCESS_KEY);
        headers.put("AppVersion", APP_VERSION);
        return headers;
    }

    /** SHA1 hex（小写） */
    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "sha1Hex failed: " + e.getMessage());
            return "";
        }
    }

    /** 屏幕 DPI（原包 r0.j()：densityDpi） */
    private String r0ScreenDpi() {
        try {
            return String.valueOf(context.getResources().getDisplayMetrics().densityDpi);
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 屏幕尺寸（原包 r0.k()："宽*高"） */
    private String r0ScreenSize() {
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            return dm.widthPixels + "*" + dm.heightPixels;
        } catch (Throwable t) {
            return "0*0";
        }
    }

    /** GET 请求（自动带签名头；返回响应体字符串，非 200 或异常返回 null） */
    private static String httpGet(String path, Map<String, String> query) throws Exception {
        return httpGet(path, query, null);
    }

    /** GET 请求（自动带签名头；可选 Authorization；返回响应体字符串，非 200 或异常返回 null） */
    private static String httpGet(String path, Map<String, String> query, String authorization) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
        }
        StringBuilder url = new StringBuilder(SERVER_HOST).append(path);
        if (!sorted.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                url.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
                first = false;
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            Map<String, String> headers = buildSignedHeaders("GET", path, query);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
            if (code != 200) {
                Log.w(TAG, "httpGet " + path + " code=" + code + " body=" + body);
                return null;
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }
}
