package com.show.p7;

import com.xpeng.p7show.R;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * P7 SHOW：底部频谱采集链路 —— 纯麦克风拾音。
 *
 * AudioRecord(AudioSource.MIC, 22050Hz, PCM16, MONO) 后台线程 read -> radix-2 FFT
 * -> 30Hz~10kHz 对数刻度 64 bin（per-bin 平滑峰值归一化）-> pushToJs(window.__audioFreq)。
 * 无声音/无权限时推全 0 基线，不伪造数据。
 */
public class MainActivity extends Activity {

    private static final String TAG = "P7Show";
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_LOCATION = 1002;
    private static final int BIN_COUNT = 64;
    private static final long PUSH_INTERVAL_MS = 160;

    // ---------- 车机歌词拉取（IcmLab 契约移植：XuiServiceManager -> IXUIService -> IMediaCenter） ----------
    private static final String XUI_SERVICE_NAME = "XuiServiceManager";
    private static final String DESC_XUI = "com.xiaopeng.xuimanager.IXUIService";
    private static final String DESC_MC = "com.xiaopeng.xuimanager.mediacenter.IMediaCenter";
    private static final int T_GET_XUI_SERVICE = 1;           // IXUIService.transact(1) 传 "mediacenter" 取 IMediaCenter
    private static final int T_GET_CURRENT_MEDIA_INFO = 19;   // IMediaCenter 当前媒体信息（title/artist）
    private static final int T_GET_CURRENT_LYRIC_INFO = 53;   // IMediaCenter 歌词：T_GET_CURRENT_LYRIC_INFO
    private static final int T_GET_CURRENT_POSITION = 20;     // IMediaCenter 播放进度：T_GET_CURRENT_POSITION（契约文档 3.3 节）
    private static final int T_GET_CURRENT_PLAY_STATUS = 18;   // IMediaCenter 播放状态：T_GET_CURRENT_PLAY_STATUS
    private static final long LYRIC_POLL_INTERVAL_MS = 1000L; // 每约 1 秒轮询一次
    private static final long LYRIC_RECONNECT_DELAY_MS = 3000L; // 连接失败重试间隔

    /** 麦克风采集参数：MIC 22050Hz PCM16 MONO，buffer 1024 采样（约 46ms 一帧） */
    private static final int MIC_SAMPLE_RATE = 22050;
    private static final int MIC_BUFFER_SAMPLES = 1024;

    /** 频率映射范围：30Hz ~ 10kHz（音乐频谱有效区间） */
    private static final double F_MIN = 30.0;
    private static final double F_MAX = 10000.0;

    private WebView webView;

    /** 红绿灯倒计时 SDK 会话（路径A：SDK 初始化 + 定位上报 + 500ms 灯态轮询） */
    private LightSdkController lightController;

    /** v1.40：内置 ADB 客户端（TCP 直连 adbd，轮询车辆属性快照） */
    private AdbClient adbClient;

    /** 麦克风采集资源 */
    private AudioRecord micRecord;
    private Thread captureThread;
    private volatile boolean captureRunning = false;

    /** 最近一次 64-bin 频谱（0~1），volatile 保证 JS 桥线程可安全读取 */
    private volatile float[] lastBins = new float[BIN_COUNT];
    private long lastPushTime = 0L;
    /** 每 bin 平滑峰值（对数映射 per-bin 归一化用，随时间衰减跟随近期峰值） */
    private float[] binSmoothMax = new float[BIN_COUNT];
    /** 当前帧原始 FFT 全局峰值（静音判断用） */
    private volatile float micFrameMax = 0f;

    /** 车机歌词：IMediaCenter binder（连接成功后缓存，轮询失败时置空触发重连） */
    private volatile IBinder mediaCenterBinder;
    private Thread lyricThread;
    private volatile boolean lyricRunning = false;
    /** 上次推送内容的去重键（歌名+歌词），内容不变不重复推送 */
    private volatile String lastLyricKey = "";
    /** 上次推送的播放位置（毫秒）；Long.MIN_VALUE 表示尚未推送过，首轮必推送 */
    private volatile long lastLyricPos = Long.MIN_VALUE;
    /** 上次推送时间（ms），用于暂停场景 5s 兜底对齐 */
    private volatile long lastLyricPushMs = 0L;
    /** 上次推送的播放状态（transact(18) 原始值）；-1 表示尚未读到。语义待真机日志确认，前端暂按 1=播放中、其余=非播放 */
    private volatile int lastPlayStatus = -1;

    /** JS 桥：JS 侧可轮询 getSpectrum() 兜底（evaluateJavascript 推送失败/未就绪时） */
    public class AndroidAudioBridge {
        @JavascriptInterface
        public String getSpectrum() {
            return binsToJson(lastBins);
        }

        /** JS 桥：读取系统状态栏高度（px）。全屏沉浸后顶部系统悬浮图标与页面内容重叠，JS 侧据此换算 vw 安全区下移。
         *  通过资源标识符 status_bar_height 读取，读取失败或异常时返回 0（由 JS 侧兜底处理）。 */
        @JavascriptInterface
        public int getStatusBarHeight() {
            try {
                int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (resId > 0) {
                    return getResources().getDimensionPixelSize(resId);
                }
            } catch (Exception e) {
                /* 读取异常返回 0 */
            }
            return 0;
        }
    }

    /** JS 桥：设置页"红绿灯倒计时"扫码激活入口（路径A）。
     *  转发 LightSdkController.scanToActivate()；结果经 window.__lightActivateResult={ok,message,ts} 回传前端，
     *  JS 侧轮询该对象更新按钮文案。返回 "pending" 表示请求已受理（真实结果以回调为准）。 */
    public class AndroidLightBridge {
        @JavascriptInterface
        public String scanToActivate() {
            if (lightController == null) {
                return "{\"ok\":false,\"message\":\"controller missing\"}";
            }
            final WebView wv = webView;
            lightController.scanToActivate(new LightSdkController.ScanActivateCallback() {
                @Override
                public void onScanResult(boolean ok, String message) {
                    final String js = "window.__lightActivateResult={ok:" + ok
                            + ",message:" + jsString(message == null ? "" : message)
                            + ",ts:" + System.currentTimeMillis() + "};";
                    if (wv != null) {
                        wv.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    wv.evaluateJavascript(js, null);
                                } catch (Exception e) { /* 页面未就绪等场景忽略 */ }
                            }
                        });
                    }
                }
            });
            return "{\"ok\":true,\"message\":\"pending\"}";
        }

        /** JS 桥：设置页"红绿灯倒计时"按钮获取微信扫码绑定激活二维码（实体化，替代 scanToActivate 占位）。
         *  转发 LightSdkController.fetchWechatQrCode()；二维码/绑定状态经 window.__lightQrData 回传前端，
         *  由 JS 侧 500ms 轮询消费。返回 "pending" 表示请求已受理。 */
        @JavascriptInterface
        public String lightFetchQrCode() {
            if (lightController == null) {
                return "{\"ok\":false,\"message\":\"controller missing\"}";
            }
            lightController.fetchWechatQrCode();
            return "{\"ok\":true,\"message\":\"pending\"}";
        }
    }

    /** 启动红绿灯 SDK 会话（幂等；onPageFinished / onResume / 定位授权后调用） */
    private void startLightController() {
        if (lightController != null) {
            lightController.start();
        }
    }

    /** 停止红绿灯 SDK 会话（onPause / onDestroy 调用） */
    private void stopLightController() {
        if (lightController != null) {
            lightController.stop();
        }
    }

    private static String binsToJson(float[] bins) {
        StringBuilder sb = new StringBuilder(bins.length * 6);
        sb.append('[');
        for (int i = 0; i < bins.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.3f", bins[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** JS 桥：v1.40 ADB 车辆数据连接（连接/断开/快照/关闭应用）。
     *  connect 返回 "pending"（真实状态经 window.__adbStatus 回传）；exitApp 关闭整个应用进程。 */
    public class AndroidAdbBridge {
        @JavascriptInterface
        public String connect(String host, int port) {
            if (adbClient == null) return "{\"ok\":false,\"message\":\"adb client missing\"}";
            return adbClient.connect(host, port);
        }

        @JavascriptInterface
        public void disconnect() {
            if (adbClient != null) adbClient.disconnect();
        }

        @JavascriptInterface
        public String getSnapshot() {
            return adbClient != null ? adbClient.getSnapshot() : "{}";
        }

        @JavascriptInterface
        public boolean isConnected() {
            return adbClient != null && adbClient.isConnected();
        }

        /** v2.x：复用底层 ADB 连接执行任意 shell 命令（D档地图：keyevent / am start） */
        @JavascriptInterface
        public String execShell(String cmd) {
            if (adbClient == null) return "{\"ok\":false,\"message\":\"adb client missing\"}";
            return adbClient.execShell(cmd);
        }

        @JavascriptInterface
        public void exitApp() {
            if (adbClient != null) adbClient.disconnect();
            finishAffinity();
            Process.killProcess(Process.myPid());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyImmersiveMode(); // 沉浸全屏：隐藏状态栏与导航栏

        webView = findViewById(R.id.webview);
        webView.setFitsSystemWindows(false); // 内容延伸到系统栏后面（配合沉浸 flags）

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 暴露 JS 桥：JS 侧轮询 getSpectrum() 作为数据兜底
        webView.addJavascriptInterface(new AndroidAudioBridge(), "AndroidAudioBridge");
        // 暴露红绿灯扫码激活 JS 桥（设置页"红绿灯倒计时"入口）
        webView.addJavascriptInterface(new AndroidLightBridge(), "AndroidLightBridge");
        // 红绿灯倒计时 SDK 会话（路径A）：传 webView 供灯态推送
        lightController = new LightSdkController(this, webView);
        // v1.40：内置 ADB 车辆数据客户端与 JS 桥（连接/断开/快照/关闭应用）
        adbClient = new AdbClient(this, webView);
        webView.addJavascriptInterface(new AndroidAdbBridge(), "AndroidAdbBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后尝试启动麦克风采集（需 RECORD_AUDIO 已授权）
                startMicCaptureIfPermitted();
                // 页面加载完成后启动红绿灯 SDK 会话（定位监听 + 500ms 灯态轮询）
                startLightController();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 授权 WebView 内媒体权限请求
                request.grant(request.getResources());
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // 前端 console 桥接进 logcat，真机诊断歌词/JS 问题（adb logcat -s P7SHOW:V *:S）
                // v33：附带行号与源文件，精确定位 JS 异常位置（此前仅 message 无法定位每帧 undefined.x 源头）
                Log.i(TAG, "[JS] " + cm.message() + " @L" + cm.lineNumber() + " src=" + cm.sourceId());
                return true;
            }
        });
        webView.setBackgroundColor(0x00000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Android 6.0+ 运行时权限：RECORD_AUDIO（麦克风拾音必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            }
        }
        // Android 6.0+ 运行时权限：定位（红绿灯倒计时 SDK 上报坐标必需；targetSdk 36 必须动态申请）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 沉浸全屏：隐藏系统状态栏与导航栏，进入应用自动隐藏。
     * Android 11+ 用 WindowInsetsController（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 允许下滑短暂显示后自动回隐）；
     * Android 10 及以下用 SYSTEM_UI_FLAG_IMMERSIVE_STICKY 兼容实现。
     */
    private void applyImmersiveMode() {
        // 车机厂商自绘顶部系统栏（车图标/里程/电量/时间）无法被标准沉浸 API 隐藏；
        // 这里退而求其次：内容延伸到系统栏后面（画满整屏）+ 状态栏透明，让应用背景铺到最顶。
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().setStatusBarColor(0x00000000); // 状态栏透明
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        // 应用级沉浸（免 root，机制同 pygxzx）：反射小鹏私有 areaType/areaId + 布局 flag +
        // 状态栏高度安全区 padding。全部反射 try/catch 兜底，失败不影响原有功能。
        applyXpAreaImmersive();
    }

    /**
     * 小鹏车机应用级沉浸（免 root，精确对齐 pygxzx smali：
     * com/xiaopeng/pengyou/ui/MainActivity#onCreate 反编译指令流）：
     * 时序 = setContentView 之后：先取 Window 属性并显式置
     *   x=0 / y=0 / width=MATCH_PARENT(-1) / height=MATCH_PARENT(-1)，
     * 再反射 areaType="immersion"、areaId=103（均先 getField、失败再
     * getDeclaredField+setAccessible 兜底），随后 getWindow().setAttributes(lp)，
     * 最后 getDecorView().setSystemUiVisibility(0x1706)（完整隐藏组合，
     * 含 FULLSCREEN|HIDE_NAVIGATION|IMMERSIVE_STICKY，覆盖旧 1792 缺隐藏位的 bug）。
     * 注意：pygxzx 的 status_bar_height padding 是加在它自己的 mRootLayout
     * （findViewById 私有 id）上，且实测对 P7 SHOW 的 android.R.id.content
     * 加 padding 会把 WebView 下挤、露出窗口黑色背景导致黑边加重——
     * 故本实现【不再】对根布局设置任何顶部 padding，WebView 保持全屏。
     * 仅影响本应用窗口，不能隐藏全局系统栏；失败时仅打日志，不影响其它功能。
     */
    private void applyXpAreaImmersive() {
        // 1) 取 Window 属性：显式置 x/y=0、宽高=MATCH_PARENT（对齐 smali）
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.x = 0;
            lp.y = 0;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;

            // 2) 反射 areaType="immersion"：getField 优先，getDeclaredField+setAccessible 兜底
            try {
                java.lang.reflect.Field fType = lp.getClass().getField("areaType");
                fType.set(lp, "immersion");
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Field fType = lp.getClass().getDeclaredField("areaType");
                    fType.setAccessible(true);
                    fType.set(lp, "immersion");
                } catch (Throwable t2) {
                    Log.w(TAG, "XPArea areaType not found: " + t2.getMessage());
                }
            }

            // 3) 反射 areaId=103：同上 fallback 链
            try {
                java.lang.reflect.Field fId = lp.getClass().getField("areaId");
                fId.setInt(lp, 103);
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Field fId = lp.getClass().getDeclaredField("areaId");
                    fId.setAccessible(true);
                    fId.setInt(lp, 103);
                } catch (Throwable t2) {
                    Log.w(TAG, "XPArea areaId not found: " + t2.getMessage());
                }
            }

            // 4) setAttributes 提交（对齐 smali：在 setContentView 之后、setSystemUiVisibility 之前）
            getWindow().setAttributes(lp);
        } catch (Throwable t) {
            Log.w(TAG, "XPArea setAttributes failed: " + t.getMessage());
        }

        // 5) setSystemUiVisibility(0x1706) = IMMERSIVE_STICKY(0x500)|FULLSCREEN(0x4)|
        //    HIDE_NAVIGATION(0x2)|LAYOUT_STABLE(0x100)|LAYOUT_FULLSCREEN(0x400)|LAYOUT_HIDE_NAVIGATION(0x200)
        //    注意：旧实现 1792(0x700) 仅含三个 LAYOUT_* 位、不含 FULLSCREEN/HIDE_NAVIGATION 真正
        //    隐藏系统栏的位，会整体覆盖 applyImmersiveMode() 已设置的隐藏 flags 导致厂商自绘状态栏
        //    重新显示；此处改为与 applyImmersiveMode() 一致的完整隐藏组合（含 IMMERSIVE_STICKY 持久隐藏）
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable t) {
            Log.w(TAG, "XPArea setSystemUiVisibility failed: " + t.getMessage());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 焦点变化可能让系统栏重新出现，重获焦点时再次隐藏（沉浸模式标准做法）
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, android.content.res.Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        // 分屏进出/比例变化会让系统状态栏重新出现，重新应用沉浸式消除顶部黑边
        applyImmersiveMode();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicCaptureIfPermitted();
        }
        if (requestCode == REQ_LOCATION && grantResults.length > 0) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            }
            // 定位权限到位后重建会话：若页面加载快于用户授权导致 start() 已被幂等短路，
            // 重建可补上定位监听（stop 内部防重复 destroy，start 幂等）
            if (granted && lightController != null) {
                lightController.stop();
                lightController.start();
            }
        }
    }

    /**
     * 在 UI 线程创建麦克风 AudioRecord 并启动后台读取循环。
     * 权限未授予时不启动，保持基线（不伪造数据）。
     */
    private void startMicCaptureIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MicCapture: RECORD_AUDIO not granted, keep baseline (no fake data)");
            return;
        }
        if (micRecord != null || !captureThreadIsIdle()) {
            return; // 已在运行
        }
        try {
            int minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(MIC_BUFFER_SAMPLES * 2, minBuf);

            AudioRecord record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(MIC_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "MicCapture: AudioRecord init failed state=" + record.getState());
                try {
                    record.release();
                } catch (Exception ignore) {
                }
                return;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "MicCapture: startRecording failed state=" + record.getRecordingState());
                try {
                    record.release();
                } catch (Exception ignore) {
                }
                return;
            }

            micRecord = record;
            captureRunning = true;
            captureThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    micCaptureLoop();
                }
            }, "mic-capture");
            captureThread.start();
            Log.i(TAG, "MicCapture: loop started sr=" + MIC_SAMPLE_RATE
                    + " bufBytes=" + bufSize + " bins=" + BIN_COUNT);
        } catch (Exception e) {
            Log.e(TAG, "MicCapture: init failed: " + e.getMessage());
            releaseMicCapture();
        }
    }

    private boolean captureThreadIsIdle() {
        return captureThread == null || !captureThread.isAlive();
    }

    /** 后台读取循环：read PCM16 -> FFT -> 对数 64 bin -> 限频推送 window.__audioFreq */
    private void micCaptureLoop() {
        byte[] buf = new byte[MIC_BUFFER_SAMPLES * 2];
        short[] samples = new short[MIC_BUFFER_SAMPLES];
        double[] re = new double[MIC_BUFFER_SAMPLES];
        double[] im = new double[MIC_BUFFER_SAMPLES];
        while (captureRunning) {
            AudioRecord rec = micRecord;
            if (rec == null) {
                break;
            }
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Exception e) {
                Log.e(TAG, "MicCapture: read failed: " + e.getMessage());
                break;
            }
            if (n <= 0) {
                if (!captureRunning) {
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            int cnt = Math.min(MIC_BUFFER_SAMPLES, n / 2);
            for (int i = 0; i < cnt; i++) {
                samples[i] = (short) ((buf[i * 2] & 0xFF) | (buf[i * 2 + 1] << 8)); // 小端 PCM16
            }

            float[] bins = estimateBinsLog(samples, cnt, re, im);
            // 静音判断：无有效输入推全 0 基线，不伪造数据
            boolean silence = micFrameMax < 2f;
            float[] out = new float[BIN_COUNT];
            for (int i = 0; i < BIN_COUNT; i++) {
                out[i] = silence ? 0f : bins[i];
            }
            lastBins = out;

            long now = System.currentTimeMillis();
            if (now - lastPushTime >= PUSH_INTERVAL_MS) {
                lastPushTime = now;
                pushToJs(out);
            }
        }
        Log.i(TAG, "MicCapture: capture thread exit");
    }

    /**
     * 分频段能量估算：1024 点 FFT 后按 30Hz~10kHz 对数刻度划分 64 bin，
     * 每 bin 取组内最大幅值并做 per-bin 平滑峰值归一化（binSmoothMax 随时间衰减）。
     * 复用调用方传入的 re/im 数组避免每帧分配。FFT 幅值未做 N 归一化，由归一化消化。
     */
    private float[] estimateBinsLog(short[] samples, int cnt, double[] re, double[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) {
            if (i < cnt) {
                re[i] = samples[i] / 32768.0;
                im[i] = 0.0;
            } else {
                re[i] = 0.0;
                im[i] = 0.0;
            }
        }
        fft(re, im);

        int half = n / 2; // 有效频点 0..half-1，频率分辨率 = SR / n
        double df = (double) MIC_SAMPLE_RATE / n; // Hz per FFT bin
        int lo = Math.max(1, (int) Math.floor(F_MIN / df));
        int hi = Math.min(half - 1, (int) Math.ceil(F_MAX / df));

        double logLo = Math.log(F_MIN);
        double logHi = Math.log(F_MAX);
        double step = (logHi - logLo) / BIN_COUNT;

        float[] raw = new float[BIN_COUNT];
        float frameMax = 0f;
        for (int b = 0; b < BIN_COUNT; b++) {
            double fStart = Math.exp(logLo + b * step);
            double fEnd = Math.exp(logLo + (b + 1) * step);
            int kStart = Math.max(lo, (int) Math.floor(fStart / df));
            int kEnd = Math.min(hi, (int) Math.ceil(fEnd / df));
            if (kEnd <= kStart) {
                raw[b] = 0f;
                continue;
            }
            float mag = 0f;
            for (int k = kStart; k < kEnd; k++) {
                double m = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (m > mag) mag = (float) m;
            }
            raw[b] = mag;
            if (mag > frameMax) frameMax = mag;
        }
        micFrameMax = frameMax; // 供静音判断

        // per-bin 平滑峰值归一化：每 bin 跟随自身近期峰值，避免低音量时柱条不可见
        float[] out = new float[BIN_COUNT];
        for (int b = 0; b < BIN_COUNT; b++) {
            float sm = binSmoothMax[b];
            binSmoothMax[b] = Math.max(raw[b], sm * 0.88f);
            float denom = binSmoothMax[b] * 1.05f + 1e-6f;
            out[b] = Math.min(1f, raw[b] / denom);
        }
        return out;
    }

    /** 迭代 radix-2 FFT（n 需为 2 的幂），原地变换 */
    private void fft(double[] re, double[] im) {
        int n = re.length;
        // bit-reversal 重排
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0;
                double ci = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    double ur = re[i + k];
                    double ui = im[i + k];
                    double vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
                    double vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + len / 2] = ur - vr;
                    im[i + k + len / 2] = ui - vi;
                    double nwr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nwr;
                }
            }
        }
    }

    /** 在 UI 线程推送 64-bin 数组到 window.__audioFreq；无播放时推全 0 数组（保持真实链路） */
    private void pushToJs(final float[] bins) {
        final WebView wv = webView;
        if (wv == null) return;
        if (bins == null) return; // 防御：不允许推送 null，避免 JS 侧回退模拟
        final String js = "window.__audioFreq=" + binsToJson(bins) + ";";
        wv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    wv.evaluateJavascript(js, null);
                } catch (Exception e) {
                    // 页面未就绪等场景忽略；JS 桥轮询可兜底
                }
            }
        });
    }

    // ====================================================================
    // 车机歌词拉取模块（IcmLab 契约移植）
    // 链路：反射 ServiceManager.getService("XuiServiceManager")
    //      -> IXUIService.transact(1) 传 "mediacenter" -> readStrongBinder 得 IMediaCenter
    //      -> IMediaCenter.transact(19) 取 title/artist；transact(53) 取 LRC 歌词
    // 后台线程每约 1 秒轮询，失败降级不崩溃（置空 binder 触发重连），
    // 通过 evaluateJavascript 推送 window.__lyricData，与频谱链路并存互不干扰。
    // ====================================================================

    /** 反射 ServiceManager.getService -> IXUIService.transact(1) -> IMediaCenter（失败返回 null） */
    private IBinder connectMediaCenter() {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object xuiObj = smCls.getMethod("getService", String.class).invoke(null, XUI_SERVICE_NAME);
            if (!(xuiObj instanceof IBinder)) {
                Log.w(TAG, "Lyric: XuiServiceManager binder is not IBinder: " + xuiObj);
                return null;
            }
            IBinder xui = (IBinder) xuiObj;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC_XUI);
                data.writeString("mediacenter");
                boolean ok = xui.transact(T_GET_XUI_SERVICE, data, reply, 0);
                if (!ok) {
                    Log.w(TAG, "Lyric: IXUIService.transact(1) failed");
                    return null;
                }
                reply.readException();
                return reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "Lyric: connect failed: " + e.getMessage());
            return null;
        }
    }

    /** IMediaCenter.transact(19) 读当前媒体信息，返回 [title, artist]；无媒体/失败返回 null */
    private String[] fetchMediaInfo(IBinder mc) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MC);
            data.writeInt(0);
            boolean ok = mc.transact(T_GET_CURRENT_MEDIA_INFO, data, reply, 0);
            if (!ok) return null;
            reply.readException();
            if (reply.readInt() == 0) return null; // has
            reply.readString(); // mediaId
            String title = reply.readString();
            String album = reply.readString();
            String artist = reply.readString();
            // 后续字段（时长/位置/比特率等）不依赖，不读取
            return new String[]{title == null ? "" : title, artist == null ? "" : artist};
        } catch (Exception e) {
            Log.w(TAG, "Lyric: media info failed: " + e.getMessage());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** IMediaCenter.transact(53) 读歌词。出参：readInt(has)+readString×6+readBundle；第2=歌名、第5=type"lrc"、第6=歌词内容 */
    private String fetchLyric(IBinder mc) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MC);
            data.writeInt(0);
            boolean ok = mc.transact(T_GET_CURRENT_LYRIC_INFO, data, reply, 0);
            if (!ok) return null;
            reply.readException();
            if (reply.readInt() == 0) return null; // has
            reply.readString(); // 占位
            reply.readString(); // 歌名（song）
            reply.readString(); // 占位
            reply.readString(); // 占位
            String type = reply.readString(); // type，期望 "lrc"
            String lyric = reply.readString(); // 歌词内容
            reply.readBundle(); // 扩展字段，不依赖
            if (lyric == null) return null;
            // 仅接受 lrc 类型；其他类型（如无歌词）按无歌词处理
            if (type != null && !type.isEmpty() && !"lrc".equalsIgnoreCase(type)) return null;
            return lyric;
        } catch (Exception e) {
            Log.w(TAG, "Lyric: fetch lyric failed: " + e.getMessage());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** IMediaCenter.transact(20) 读当前播放位置。出参：readInt(n)+readLong×n；
     *  真机实测（2026-10-15 车机日志）：数组为 [当前曲目播放秒数, 当前曲目总时长秒数]——
     *  索引 0 播放中每秒+1、暂停冻结、拖动进度条跳变、切歌重置为 1；索引 1 仅在切歌时变化。
     *  修正：取索引 0 并乘 1000 转毫秒返回（此前盲取最大非负值拿到的是总时长、且单位秒被前端当毫秒，
     *  本地时钟被拉到 0.0x 秒位置，歌词永远卡在开头）。全部无效返回 -1；打印原始数组便于核对。 */
    private long fetchPosition(IBinder mc) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MC);
            data.writeInt(0);
            boolean ok = mc.transact(T_GET_CURRENT_POSITION, data, reply, 0);
            if (!ok) return -1;
            reply.readException();
            int n = reply.readInt();
            if (n <= 0) return -1;
            long[] vals = new long[n];
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                vals[i] = reply.readLong();
                if (i > 0) sb.append(',');
                sb.append(vals[i]);
            }
            sb.append(']');
            long posMs = -1;
            if (n >= 1 && vals[0] >= 0) posMs = vals[0] * 1000L; // 索引 0 = 当前播放秒数 → 毫秒
            Log.i(TAG, "Lyric: position array " + sb + " -> posMs=" + posMs);
            return posMs;
        } catch (Exception e) {
            Log.w(TAG, "Lyric: fetch position failed: " + e.getMessage());
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** IMediaCenter.transact(18) 读当前播放状态。出参：readInt(status)；
     *  契约仅标注返回 int status，取值语义未定义；这里透传原始值给前端并打日志，
     *  真机 logcat 可核对（adb logcat -s P7SHOW:V *:S，关键字 Lyric: play status）。失败返回 -1。 */
    private int fetchPlayStatus(IBinder mc) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_MC);
            data.writeInt(0);
            boolean ok = mc.transact(T_GET_CURRENT_PLAY_STATUS, data, reply, 0);
            if (!ok) return -1;
            reply.readException();
            int status = reply.readInt();
            Log.i(TAG, "Lyric: play status=" + status);
            return status;
        } catch (Exception e) {
            Log.w(TAG, "Lyric: fetch play status failed: " + e.getMessage());
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 后台轮询循环：连接 -> 取 media info + lyric + position + play status -> 去重推送；失败降级重连 */
    private void lyricPollLoop() {
        while (lyricRunning) {
            try {
                if (mediaCenterBinder == null) {
                    mediaCenterBinder = connectMediaCenter();
                    if (mediaCenterBinder == null) {
                        Thread.sleep(LYRIC_RECONNECT_DELAY_MS);
                        continue;
                    }
                }
                IBinder mc = mediaCenterBinder;
                String[] media = fetchMediaInfo(mc);
                String song = (media != null) ? media[0] : "";
                String artist = (media != null) ? media[1] : "";
                String lyric = fetchLyric(mc);
                long pos = fetchPosition(mc); // 当前播放位置（毫秒），失败为 -1
                int status = fetchPlayStatus(mc); // 播放状态（transact(18) 原始值），失败为 -1

                // 去重：歌词内容变化、播放位置漂移 >=1s、距上次推送 >=1s（暂停时 pos 不变也 1s 兜底对齐）、或播放状态变化（暂停/恢复立即推送）时推送
                // 按车机实况每秒推送，兼顾拖进度条响应
                String key = song + "\u0001" + artist + "\u0001" + (lyric == null ? "" : lyric);
                boolean lyricChanged = !key.equals(lastLyricKey);
                long nowMs = System.currentTimeMillis();
                boolean posDrifted = (pos >= 0) && (Math.abs(pos - lastLyricPos) >= 1000L);
                boolean timeElapsed = (pos >= 0) && (nowMs - lastLyricPushMs >= 1000L);
                boolean statusChanged = (status >= 0) && (status != lastPlayStatus);
                if (lyricChanged || posDrifted || timeElapsed || statusChanged) {
                    lastLyricKey = key;
                    lastLyricPos = pos;
                    if (status >= 0) lastPlayStatus = status;
                    lastLyricPushMs = nowMs;
                    pushLyricToJs(song, artist, lyric, pos, status);
                }
                Thread.sleep(LYRIC_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "Lyric: poll loop error: " + e.getMessage());
                mediaCenterBinder = null; // 重置连接，下轮重连
                try {
                    Thread.sleep(LYRIC_RECONNECT_DELAY_MS);
                } catch (InterruptedException ie2) {
                    break;
                }
            }
        }
        Log.i(TAG, "Lyric: poll thread exit");
    }

    /** 启动歌词轮询线程（幂等） */
    private void startLyricPolling() {
        if (lyricRunning) return;
        lyricRunning = true;
        lyricThread = new Thread(new Runnable() {
            @Override
            public void run() {
                lyricPollLoop();
            }
        }, "lyric-poll");
        lyricThread.start();
    }

    /** 停止歌词轮询线程（onPause / onDestroy） */
    private void stopLyricPolling() {
        lyricRunning = false;
        if (lyricThread != null) {
            try {
                lyricThread.interrupt();
            } catch (Exception ignore) { /* ignore */ }
            lyricThread = null;
        }
        mediaCenterBinder = null;
    }

    /** UI 线程推送 window.__lyricData = {song, artist, lyric, pos, status, ts}；无歌词时 lyric 为 null（前端显示占位）；pos 为毫秒播放位置，无则 -1；status 为播放状态原始值，无则 -1 */
    private void pushLyricToJs(final String song, final String artist, final String lyric, final long pos, final int status) {
        final WebView wv = webView;
        if (wv == null) return;
        final String js = "window.__lyricData={song:" + jsString(song == null ? "" : song)
                + ",artist:" + jsString(artist == null ? "" : artist)
                + ",lyric:" + (lyric == null ? "null" : jsString(lyric))
                + ",pos:" + pos
                + ",status:" + status
                + ",ts:" + System.currentTimeMillis() + "};";
        wv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    wv.evaluateJavascript(js, null);
                } catch (Exception e) {
                    // 页面未就绪等场景忽略
                }
            }
        });
    }

    /** JS 字符串转义：反斜杠/引号/换行/回车/Tab/控制字符 */
    private static String jsString(String s) {
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

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        // 恢复采集（onPause 已停读释放；权限已授予则重建）
        startMicCaptureIfPermitted();
        // 恢复车机歌词轮询
        startLyricPolling();
        // 恢复红绿灯 SDK 会话（定位监听 + 灯态轮询；start 幂等）
        startLightController();
    }

    @Override
    protected void onPause() {
        stopMicCaptureReadLoop(); // 停止后台读取并释放 AudioRecord
        stopLyricPolling();       // 停止歌词轮询
        stopLightController();    // 停止红绿灯 SDK 会话（定位监听 + 灯态轮询）
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseMicCapture();
        stopLyricPolling();
        stopLightController(); // 停止红绿灯 SDK 会话并释放原生会话
        if (adbClient != null) adbClient.disconnect(); // v1.40：断开 ADB 连接
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }

    /** 停止后台读取并释放 AudioRecord（onPause / onDestroy 共用） */
    private void stopMicCaptureReadLoop() {
        captureRunning = false;
        if (captureThread != null) {
            try {
                captureThread.join(300);
            } catch (InterruptedException e) { /* ignore */ }
            captureThread = null;
        }
        if (micRecord != null) {
            try {
                micRecord.stop();
            } catch (Exception e) { /* ignore */ }
            try {
                micRecord.release();
            } catch (Exception e) { /* ignore */ }
            micRecord = null;
        }
        Log.i(TAG, "MicCapture: read loop stopped");
    }

    /** 释放麦克风采集全部资源（onDestroy 调用） */
    private void releaseMicCapture() {
        stopMicCaptureReadLoop();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
