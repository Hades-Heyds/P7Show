package com.show.p7;

import android.app.Activity;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * P7 SHOW 内置 ADB 客户端（v1.41）。
 *
 * TCP 直连 host:port 的 adbd，自实现 ADB 协议。v1.41 按打灯项目 CameraAssist v1.44
 * 实车跑通的认证方案重写握手部分（修复 v1.40 read timed out）：
 *   1) AUTH 类型常量修正为 TOKEN=1 / SIGNATURE=2 / PUBKEY=3（v1.40 的 TOKEN=2/SIGNATURE=3 是握手卡死根因）；
 *   2) CNXN version 改标准 0x01000001，banner 用 "host::features=shell_v2,cmd,stat_v2\0"，maxdata 256KB；
 *   3) 签名改「伪 SHA1 裸 RSA」：DigestInfo(SHA1) 头 + token 原文拼接，PKCS#1 v1.5 填充后 modPow(d,n)，
 *      大端左对齐 256 字节；不再用 SHA1 摘要 + Cipher PKCS1Padding；
 *   4) 公钥改 ADB blob（TYPE=3 PUBKEY 载荷）：words|n0inv|N|RR=R² mod N|e 全小端，由 RSA2048 私钥动态推导；
 *   5) 签名被拒后发 TYPE=3 公钥并启动独立配对线程：180s 超时、30s 无响应重发公钥、期间保持 socket 不关
 *      （否则车机授权弹窗消失），用户点「允许」后同连接回 CNXN 即配对成功。
 *
 * 保留 v1.40 的 connect/disconnect/getSnapshot/isConnected 接口、OPEN/WRTE/OKAY/CLSE 轮询
 * （dumpsys car_service 解析）与 pushStatus/pushJs 回传逻辑，只改认证握手。
 * 密钥仍用 filesDir 持久化的 RSA2048（PKCS8 DER 私钥），公钥 blob 由私钥动态推导。
 *
 * v1.44 对照打灯版 CameraAssist 已验证 exec 实现修复轮询收发（v1.43 poll recv 恒 0 根因）：
 *   a) OPEN 直接携带 "shell:<DUMP_CMD>\0"（命令随 OPEN 发送、\0 结尾，adbd exec 执行），
 *      不再 OPEN "shell:" 后用 WRTE 写 stdin（交互式 shell 路径实测收不到任何输出）；
 *   b) 收到 WRTE 回 OKAY 修正为 arg0=localId, arg1=remoteId（v1.43 反序错误）；
 *   c) 接收改为阻塞 readFrame 读完整帧，socket soTimeout 兜底（v1.43 依赖 in.available()>0 轮询漏读）；
 *   d) 每轮独立 OPEN/CLSE 流，底层已认证 socket 保持复用；2s 轮询周期、DUMP_CMD、解析回传不变。
 *
 * 轮询命令（每 2s 一次）：
 *   dumpsys car_service | grep -E 'lastEvent: Property=VCU_CURRENT_GEARLEV|...|BCM_BONNET'
 * 解析 lastEvent 行内的 int32Values / floatValues 段，构建车辆属性快照并经
 * evaluateJavascript 写入 window.__adbStatus / window.__adbSnapshot 回传前端。
 */
public class AdbClient {

    private static final String TAG = "P7ShowAdb";

    // ADB 命令字（4 字节 ASCII 按小端解释）
    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"

    // v1.41：AUTH 类型常量按标准 ADB 协议修正（v1.40 的 TOKEN=2/SIGNATURE=3 定义错误，是握手卡死根因）
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBKEY = 3;

    /** CNXN 声明版本：标准 ADB 握手版本 0x01000001（v1.40 的 0x01000000 部分车机 adbd 不认） */
    private static final int A_VERSION = 0x01000001;
    /** CNXN 声明的接收缓冲区上限 */
    private static final int MAX_PAYLOAD = 256 * 1024;

    // v1.42：车辆信息固定每 2 秒轮询一次（用户反馈 v1.41 数据读不上/只读一次，改为明确 2000ms 周期持续循环）
    private static final long POLL_INTERVAL_MS = 400L; // v1.46：档位轮询提速至 0.4 秒
    /** 单周期内读取 dumpsys 输出的收包超时：放宽到 3s，确保 dumpsys 输出完整收完再解析推送 */
    private static final long POLL_READ_TIMEOUT_MS = 600L; // v1.46：随 0.4s 轮询收窄，确保单周期收完整
    /** v2.x：execShell 单命令整体执行超时（含收输出） */
    private static final long EXEC_TIMEOUT_MS = 2000L;
    /** AUTH 阶段读超时：放宽到 60s，给用户留足时间在车机弹窗上点授权 */
    private static final int AUTH_READ_TIMEOUT_MS = 60000;
    /** 配对（首次授权）等待上限：等用户在车机弹窗点允许 */
    private static final long PAIR_TIMEOUT = 180_000L;
    /** 配对等待期间无响应时，周期重发 TYPE=3 公钥，避免授权弹窗被系统收走 */
    private static final long PAIR_REPUB_GAP = 30_000L;

    /** DigestInfo(SHA1) = 30 21 30 09 06 05 2B 0E 03 02 1A 05 00 04 14 */
    private static final byte[] DI_SHA1 = new byte[]{
            (byte) 0x30, (byte) 0x21, (byte) 0x30, (byte) 0x09, (byte) 0x06, (byte) 0x05,
            (byte) 0x2B, (byte) 0x0E, (byte) 0x03, (byte) 0x02, (byte) 0x1A, (byte) 0x05,
            (byte) 0x00, (byte) 0x04, (byte) 0x14
    };

    /** 轮询命令：一条 grep -E 抓全全部目标属性（只读 lastEvent 段） */
    // v1.45：新增电耗（VCU_VEHELC_CONSP_100M / CONSP_AVG）、充电电压/电流（VCU_OBC_DCVOLT / OBC_DCCUR）、
    // 灯光状态（左/右转向灯、雾灯、近光灯、前灯组模式）共 9 个属性，加入同一 grep 表达式，保持 2s 轮询。
    // v1.54：新增快充电流/累计（VCU_DCCCS_CURR / DCCCS_SUMU，实车走 floatValues），解析层 float 优先。
    // v2.12：新增 CDU_IBCM_FLPOS/FRPOS/RLPOS/RRPOS 四门位置属性（实车确认存在，BCM_DOOR 为四门数组非位掩码）。
    private static final String DUMP_CMD =
            "dumpsys car_service | grep -E 'lastEvent: Property=VCU_CURRENT_GEARLEV|VCU_BMS_SOCDISP|VCU_DSTBAT_DISP|VCU_DSTBAT_DISP_CLTC|VCU_DSTBAT_DISP_WLTP_FLOAT|VCU_DSTBAT_DISP_NEDC_FLOAT|VCU_CHARGE_STATUS|VCU_CHARGE_GUN_STATUS|VCU_ACCPEDAL_SIG|VCU_BRKPEDAL_ST|BCM_DOOR|BCM_TRUNKAJAR|BCM_BONNET|VCU_VEHELC_CONSP_100M|VCU_VEHELC_CONSP_AVG|VCU_OBC_DCVOLT|VCU_OBC_DCCUR|VCU_DCCCS_CURR|VCU_DCCCS_SUMU|BCM_LTURNLAMP_ST|BCM_RTURNLAMP_ST|FOG_LIGHTS_SWITCH_XP|BCM_LOW_BEAM|BCM_FRONT_LIGHT_GROUP_MODE|CDU_IBCM_FLPOS|CDU_IBCM_FRPOS|CDU_IBCM_RLPOS|CDU_IBCM_RRPOS'\n";

    private final Activity activity;
    private final WebView webView;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread worker;
    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile String lastError = null;
    private volatile String lastSnapshot = "{}";
    private volatile long lastSnapshotTs = 0L;

    /** v1.44：每轮轮询独立 OPEN 新流，localId 递增（对照打灯版 exec 模式） */
    private int nextLocalId = 0x4e000001;

    /** v2.x：IO 锁——保护 socket 帧读写，串行化轮询线程与 execShell 命令流，避免帧交错 */
    private final Object ioLock = new Object();

    private PrivateKey privKey;
    /** 配对授权等待中（独立线程负责，期间保持 socket 不关，否则车机授权弹窗消失） */
    private volatile boolean pairing = false;

    private static final Pattern PROP_NAME = Pattern.compile("Property=(\\w+)");
    private static final Pattern INT32_SEG = Pattern.compile("int32Values:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);
    private static final Pattern FLOAT_SEG = Pattern.compile("floatValues:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL);

    /** 车辆属性快照（线程安全：整体替换引用） */
    private static class Snapshot {
        long ts;
        Integer gear, soc, rangeCur;
        Float rangeCltc, rangeWltp, rangeNedc;
        Integer chargeStatus, chargeGun, accPedal, brkPedal, doors, trunk, bonnet;
        int[] doorsArr; // v2.12：BCM_DOOR 四门 int32Values 数组（实车 [前左,前右,后左,后右]，非位掩码）
        Map<String, Float> doorPos; // v2.13：CDU_IBCM_FLPOS/FRPOS/RLPOS/RRPOS 四门位置属性（int32/float 双通道收集）
        Map<String, Integer> doorProps; // v2.11：全部 BCM_DOOR* 属性透传（真车仅 BCM_DOOR 时为空）
        // v1.45：电耗 / 充电电压电流 / 灯光状态（int32/float 两种上报形态统一按 Float 存）
        Float consp100m, conspAvg, obcVolt, obcCur, lturn, rturn, fog, lowBeam, frontLightGroup;
        // v1.54：快充电流 / 累计（VCU_DCCCS_CURR / DCCCS_SUMU，实车走 floatValues）
        Float dccsCurr, dccsSumu;
    }

    private static class Frame {
        final int cmd;
        final int arg0;
        final int arg1;
        final byte[] payload;
        Frame(int cmd, int arg0, int arg1, byte[] payload) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }

    public AdbClient(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** JS 桥入口：开始异步连接；返回 "pending"（真实状态经 window.__adbStatus 回传） */
    public synchronized String connect(final String host, final int port) {
        if (connected) {
            pushStatus(true, host, port);
            return "{\"ok\":true,\"message\":\"already connected\"}";
        }
        disconnect();
        stopped = false;
        final String h = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host.trim();
        final int p = port;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    connectLoop(h, p);
                } catch (Throwable t) {
                    if (!stopped) {
                        lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                        pushStatus(false, null, 0);
                    }
                }
            }
        }, "P7Show-AdbWorker");
        worker.setDaemon(true);
        worker.start();
        return "pending";
    }

    public synchronized void disconnect() {
        stopped = true;
        connected = false;
        if (worker != null) {
            Thread w = worker;
            worker = null;
            try { w.interrupt(); } catch (Exception ignore) { /* 忽略 */ }
        }
        closeQuietly();
    }

    public boolean isConnected() {
        return connected;
    }

    /** JS 桥入口：返回最近一次车辆属性快照 JSON */
    public String getSnapshot() {
        return lastSnapshot;
    }

    /**
     * JS 桥入口：复用底层已认证 ADB 连接执行任意 shell 命令（v2.x D档地图）。
     *
     * 与轮询相同协议：OPEN "shell:<cmd>\0" -> OKAY -> WRTE（收输出回 OKAY）-> CLSE；
     * 通过 ioLock 与轮询线程串行化帧读写，不关底层 socket。
     * 在独立线程执行避免阻塞 JS 桥调用线程；返回 JSON {ok, output|message}。
     */
    public String execShell(final String cmd) {
        if (!connected || sock == null || in == null || out == null) {
            return "{\"ok\":false,\"message\":\"not connected\"}";
        }
        if (cmd == null || cmd.trim().isEmpty()) {
            return "{\"ok\":false,\"message\":\"empty cmd\"}";
        }
        final StringBuilder result = new StringBuilder();
        final boolean[] ok = {false};
        final String[] errMsg = {null};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (ioLock) {
                    int lid = nextLocalId++;
                    int rid = 0;
                    boolean streamOpen = true;
                    long deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS;
                    try {
                        // 临时放宽读超时到命令整体超时，避免轮询的 600ms soTimeout 截断命令输出；结束后恢复
                        int oldTimeout = 0;
                        try {
                            if (sock != null) oldTimeout = sock.getSoTimeout();
                        } catch (IOException ignore) { /* 忽略 */ }
                        try {
                            if (sock != null) sock.setSoTimeout((int) EXEC_TIMEOUT_MS);
                        } catch (IOException ignore) { /* 忽略 */ }
                        byte[] openPayload = ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8);
                        sendFrame(A_OPEN, lid, 0, openPayload);
                        while (streamOpen && !stopped && System.currentTimeMillis() < deadline) {
                            Frame f;
                            try {
                                f = readFrame();
                            } catch (java.net.SocketTimeoutException ste) {
                                break;
                            }
                            if (f == null) break;
                            if (f.cmd == A_OKAY && f.arg1 == lid) {
                                rid = f.arg0;
                            } else if (f.cmd == A_WRTE && f.arg1 == lid) {
                                result.append(new String(f.payload, StandardCharsets.UTF_8));
                                sendFrame(A_OKAY, lid, f.arg0, new byte[0]);
                            } else if (f.cmd == A_CLSE && f.arg1 == lid) {
                                streamOpen = false;
                            }
                        }
                        try {
                            sendFrame(A_CLSE, lid, rid, new byte[0]);
                        } catch (IOException ignored) { /* 流已结束可忽略 */ }
                        try {
                            if (sock != null) sock.setSoTimeout(oldTimeout);
                        } catch (IOException ignore) { /* 忽略 */ }
                        ok[0] = true;
                    } catch (Throwable th) {
                        errMsg[0] = th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage();
                    }
                }
            }
        }, "P7Show-AdbExec");
        t.start();
        try {
            t.join(EXEC_TIMEOUT_MS + 1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ok[0]) {
            return "{\"ok\":false,\"message\":" + jsStr(errMsg[0] != null ? errMsg[0] : "timeout") + "}";
        }
        return "{\"ok\":true,\"output\":" + jsStr(result.toString().trim()) + "}";
    }

    private void connectLoop(String host, int port) throws Exception {
        Socket s = new Socket();
        boolean keepSocket = false; // OPEN 成功后由轮询循环接管 socket
        int localId = 1;
        int remoteId = 0;
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            Log.i(TAG, "socket connected to " + host + ":" + port);
            // AUTH 阶段使用独立长超时（60s）：给用户充足时间在车机弹窗上点授权
            s.setSoTimeout(AUTH_READ_TIMEOUT_MS);
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
            sock = s;

            ensureKey();

            // 1) CNXN（version=0x01000001 标准握手版本, maxdata, banner）
            byte[] banner = "host::features=shell_v2,cmd,stat_v2\0".getBytes(StandardCharsets.UTF_8);
            sendFrame(A_CNXN, A_VERSION, MAX_PAYLOAD, banner);

            // 2) AUTH 循环：TOKEN -> 回伪 SHA1 裸 RSA 签名；
            //    签名被拒（再次收到 TOKEN）-> 发 TYPE=3 PUBKEY（ADB blob）并转独立配对线程等待用户授权
            boolean authed = false;
            boolean signed = false;
            for (int i = 0; i < 12 && !stopped; i++) {
                Frame f = readFrame();
                if (f == null) break;
                if (f.cmd == A_AUTH && f.arg0 == AUTH_TOKEN) {
                    if (!signed) {
                        byte[] sig = signToken(f.payload);
                        sendFrame(A_AUTH, AUTH_SIGNATURE, 0, sig);
                        signed = true;
                        continue;
                    }
                    // 签名被拒 = 车机未授权本应用公钥 -> 发 TYPE=3 PUBKEY 并进入配对等待
                    byte[] pub = pubkeyPayload();
                    sendFrame(A_AUTH, AUTH_PUBKEY, 0, pub);
                    lastError = "ADB 配对等待中，请在车机弹窗点击允许";
                    pushStatus(false, host, port);
                    if (!waitForPairing()) {
                        throw new IOException("ADB 配对等待超时（请在车机弹窗点击允许）");
                    }
                    authed = true;
                    break;
                } else if (f.cmd == A_CNXN) {
                    authed = true;
                    break;
                }
            }
            if (authed) {
                Log.i(TAG, "CNXN received, auth ok");
            }
            if (!authed && !stopped) {
                throw new IOException("ADB auth failed");
            }

            // 3) 轮询 dumpsys（v1.44：对照打灯版 exec 模式，每轮独立 OPEN shell:<DUMP_CMD>\0 -> OKAY -> WRTE -> CLSE）
            connected = true;
            lastError = null;
            pushStatus(true, host, port);
            keepSocket = true;
            s.setSoTimeout((int) POLL_READ_TIMEOUT_MS);

            while (!stopped) {
                long t0 = System.currentTimeMillis();
                int lid = nextLocalId++;
                int rid = 0;
                try {
                    // 3a) OPEN shell:<DUMP_CMD>\0：命令随 OPEN 发送（\0 结尾），adbd 直接 exec，
                    //     不再 OPEN "shell:" 后依赖 WRTE 写 stdin（v1.43 交互 shell 路径收不到任何输出）
                    byte[] openPayload = ("shell:" + DUMP_CMD + "\0").getBytes(StandardCharsets.UTF_8);
                    synchronized (ioLock) {
                        sendFrame(A_OPEN, lid, 0, openPayload);
                    }
                    Log.i(TAG, "poll open localId=0x" + Integer.toHexString(lid)
                            + " cmd=" + (DUMP_CMD.length() > 160 ? DUMP_CMD.substring(0, 160) + "..." : DUMP_CMD));

                    // 3b) 收 OKAY/WRTE/CLSE：阻塞 readFrame 读完整帧（v1.43 用 in.available()>0 轮询会漏读）
                    StringBuilder sb = new StringBuilder();
                    boolean streamOpen = true;
                    while (streamOpen && !stopped) {
                        Frame f;
                        try {
                            synchronized (ioLock) {
                                f = readFrame();
                            }
                        } catch (java.net.SocketTimeoutException ste) {
                            // 本轮收包超时：只结束本轮，连接保持复用，下一轮继续
                            Log.w(TAG, "poll recv timeout after " + POLL_READ_TIMEOUT_MS + "ms, partial=" + sb.length());
                            break;
                        }
                        if (f == null) break;
                        if (f.cmd == A_OKAY && f.arg1 == lid) {
                            rid = f.arg0;
                            Log.i(TAG, "poll okay remoteId=0x" + Integer.toHexString(rid));
                        } else if (f.cmd == A_WRTE && f.arg1 == lid) {
                            sb.append(new String(f.payload, StandardCharsets.UTF_8));
                            // 打灯版：回 OKAY arg0=localId, arg1=remoteId（v1.43 写成 arg0=rid,arg1=lid 反了）
                            synchronized (ioLock) {
                                sendFrame(A_OKAY, lid, f.arg0, new byte[0]);
                            }
                            String first = sb.length() > 80 ? sb.substring(0, 80) : sb.toString();
                            Log.i(TAG, "poll recv len=" + f.payload.length + " first=" + first);
                        } else if (f.cmd == A_CLSE && f.arg1 == lid) {
                            streamOpen = false;
                            Log.i(TAG, "poll clse");
                        }
                    }
                    // 3c) CLSE 关闭本轮流（仅结束当前 stream，不关底层 socket，供下轮复用）
                    try {
                        synchronized (ioLock) {
                            sendFrame(A_CLSE, lid, rid, new byte[0]);
                        }
                    } catch (IOException ignored) { }
                    parseAndPush(sb.toString());
                } catch (IOException e) {
                    if (!stopped) {
                        connected = false;
                        lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        pushStatus(false, null, 0);
                    }
                    break;
                }
                long el = System.currentTimeMillis() - t0;
                long wait = POLL_INTERVAL_MS - el;
                if (wait > 0 && !stopped) Thread.sleep(wait);
            }
        } finally {
            // 握手/配对/OPEN 失败路径：确保 socket 释放（配对线程失败时已自行 closeQuietly，此处幂等）
            if (!keepSocket) closeQuietly();
        }

        Log.i(TAG, "worker stopped");

        // 5) 退出清理
        try { sendFrame(A_CLSE, localId, remoteId, new byte[0]); } catch (Exception ignore) { /* 忽略 */ }
        connected = false;
        closeQuietly();
    }

    /**
     * 独立配对线程：签名被拒后发 TYPE=3 公钥，等待用户在车机屏幕点「允许」授权（首次 / 换密钥后）。
     *
     * 保持 socket 不关：授权弹窗绑定在这条 TCP 连接上，客户端一断 adbd 立即关掉弹窗。
     * 180s 总超时内每 30s 无响应重发 TYPE=3 公钥，防止弹窗被系统收走；
     * 收到 CNXN 即配对成功。配对线程 2s 轮询读（soTimeout=2000），期间不占用轮询主流程。
     */
    private boolean waitForPairing() throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        pairing = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    if (sock != null) sock.setSoTimeout(2000);
                    long deadline = System.currentTimeMillis() + PAIR_TIMEOUT;
                    long lastPub = System.currentTimeMillis();
                    while (System.currentTimeMillis() < deadline && !stopped) {
                        Frame p;
                        try {
                            p = readFrame();
                        } catch (java.net.SocketTimeoutException ste) {
                            // 用户还没动作 -> 超过 30s 重发公钥，避免授权弹窗被系统收走
                            if (System.currentTimeMillis() - lastPub > PAIR_REPUB_GAP) {
                                sendFrame(A_AUTH, AUTH_PUBKEY, 0, pubkeyPayload());
                                lastPub = System.currentTimeMillis();
                            }
                            continue;
                        }
                        if (p == null) break;
                        if (p.cmd == A_CNXN) {
                            Log.i(TAG, "收到 CNXN，配对成功");
                            success = true;
                            break;
                        } else if (p.cmd == A_AUTH && p.arg0 == AUTH_TOKEN) {
                            sendFrame(A_AUTH, AUTH_PUBKEY, 0, pubkeyPayload());
                            lastPub = System.currentTimeMillis();
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "ADB 配对线程异常: " + e.getMessage(), e);
                } finally {
                    ok[0] = success;
                    latch.countDown();
                    if (!success) {
                        // 配对失败：关闭连接，等待下次重试（成功时 socket 交由轮询主流程继续复用）
                        closeQuietly();
                    }
                }
            }
        }, "P7Show-AdbPair");
        t.start();
        try {
            if (!latch.await(PAIR_TIMEOUT + 5000, TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pairing = false;
        }
        return ok[0];
    }

    // ---------- ADB 帧 ----------

    private void sendFrame(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        DataOutputStream o = out;
        if (o == null) throw new IOException("not connected");
        byte[] p = payload == null ? new byte[0] : payload;
        int crc = 0;
        if (p.length > 0) {
            CRC32 c = new CRC32();
            c.update(p);
            crc = (int) c.getValue();
        }
        writeIntLE(o, cmd);
        writeIntLE(o, arg0);
        writeIntLE(o, arg1);
        writeIntLE(o, p.length);
        writeIntLE(o, crc);
        writeIntLE(o, ~cmd); // v1.41：magic 用 ~command（标准协议），与实车跑通方案一致
        if (p.length > 0) o.write(p);
        o.flush();
    }

    private Frame readFrame() throws IOException {
        DataInputStream i = in;
        if (i == null) throw new IOException("not connected");
        byte[] h = new byte[24];
        int off = 0;
        while (off < 24) {
            int n = i.read(h, off, 24 - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
        int cmd = readIntLE(h, 0);
        int arg0 = readIntLE(h, 4);
        int arg1 = readIntLE(h, 8);
        int len = readIntLE(h, 12);
        // v1.41：与实车跑通方案一致，接收端不校验 crc/magic（部分车机 adbd 与标准实现不一致）
        if (len < 0 || len > 4 * 1024 * 1024) throw new IOException("bad length " + len);
        byte[] payload = new byte[len];
        off = 0;
        while (off < len) {
            int n = i.read(payload, off, len - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
        return new Frame(cmd, arg0, arg1, payload);
    }

    private static void writeIntLE(DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 0xff);
        o.writeByte((v >>> 8) & 0xff);
        o.writeByte((v >>> 16) & 0xff);
        o.writeByte((v >>> 24) & 0xff);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    // ---------- RSA2048 密钥与 ADB 认证 ----------

    private void ensureKey() throws Exception {
        File dir = activity.getFilesDir();
        File privF = new File(dir, "adb_key_priv");
        if (privF.exists() && privF.length() > 0) {
            byte[] privEnc = readAll(privF);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privEnc));
            return;
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privKey = kp.getPrivate();
        try {
            writeAll(privF, privKey.getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "persist adb key failed", e);
        }
    }

    /**
     * 伪 SHA1 裸 RSA 签名（实车跑通方案）：
     * EM = 00 01 FF..FF 00 DigestInfo(SHA1) + token，s = EM^d mod n，大端左对齐输出 256 字节。
     * 不再用 SHA1 摘要 + Cipher PKCS1Padding。
     */
    private byte[] signToken(byte[] token) throws Exception {
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privKey;
        BigInteger n = rsa.getModulus();
        BigInteger d = rsa.getPrivateExponent();
        int k = (n.bitLength() + 7) / 8; // 模长字节数（2048 位 => 256）

        byte[] tok = token == null ? new byte[0] : token;
        byte[] body = new byte[DI_SHA1.length + tok.length];
        System.arraycopy(DI_SHA1, 0, body, 0, DI_SHA1.length);
        System.arraycopy(tok, 0, body, DI_SHA1.length, tok.length);

        int padLen = k - 3 - body.length;
        if (padLen < 8) throw new IllegalStateException("RSA 密钥过小，无法构造 PKCS#1 v1.5 填充");

        byte[] em = new byte[k];
        em[0] = 0x00;
        em[1] = 0x01;
        for (int i = 2; i < 2 + padLen; i++) em[i] = (byte) 0xFF;
        em[2 + padLen] = 0x00;
        System.arraycopy(body, 0, em, 3 + padLen, body.length);

        BigInteger sig = new BigInteger(1, em).modPow(d, n);
        byte[] sb = sig.toByteArray();
        byte[] out = new byte[k];
        if (sb.length > k) {
            System.arraycopy(sb, sb.length - k, out, 0, k); // 截断高位 0 前缀
        } else {
            System.arraycopy(sb, 0, out, k - sb.length, sb.length); // 大端左对齐，不翻转
        }
        return out;
    }

    /**
     * ADB AUTH TYPE=3 PUBKEY 载荷：base64(ADB 公钥 blob) + " 注释" + '\0'。
     * blob 结构（全部小端）：字数(4B) | n0inv(4B) | 模数 N(字数字节) | RR = R² mod N(同长) | e(4B)。
     * 与打灯项目实车跑通方案一致：从内置私钥动态推导公钥参数（RSAPrivateCrtKey 取 modulus/publicExponent），
     * 不再用 X.509 DER SPKI。
     */
    private byte[] pubkeyPayload() throws Exception {
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privKey;
        BigInteger n = rsa.getModulus();
        BigInteger e = rsa.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;

        byte[] nb = leFixed(n, words * 4);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        byte[] rrb = leFixed(r.multiply(r).mod(n), words * 4); // RR = R² mod N

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xffL);
        BigInteger inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32));
        long n0inv = (-inv.longValue()) & 0xFFFFFFFFL;

        ByteBuffer bb = ByteBuffer.allocate(8 + nb.length + rrb.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt((int) n0inv);
        bb.put(nb);
        bb.put(rrb);
        bb.putInt(e.intValue());

        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return (b64 + " p7show@xpeng\0").getBytes(StandardCharsets.UTF_8);
    }

    /** 大端整数 -> 定长小端字节。 */
    private static byte[] leFixed(BigInteger v, int bytes) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[bytes];
        for (int i = 0; i < Math.min(be.length, bytes); i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = fis.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } finally {
            fis.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            fos.close();
        }
    }

    // ---------- dumpsys 解析 ----------

    private void parseAndPush(String text) {
        if (text == null || text.isEmpty()) return;
        Map<String, Integer> intVals = new HashMap<>();
        Map<String, Float> floatVals = new HashMap<>();
        Matcher pm = PROP_NAME.matcher(text);
        int lastIdx = 0;
        String lastName = null;
        String doorSeg = null; // v2.12：BCM_DOOR 完整属性段（int32Values 四门数组需整体解析）
        while (pm.find()) {
            if (lastName != null) {
                String seg = text.substring(lastIdx, pm.start());
                parseSeg(lastName, seg, intVals, floatVals);
                if ("BCM_DOOR".equals(lastName)) doorSeg = seg;
            }
            lastName = pm.group(1);
            lastIdx = pm.start();
        }
        if (lastName != null) {
            String seg = text.substring(lastIdx);
            parseSeg(lastName, seg, intVals, floatVals);
            if ("BCM_DOOR".equals(lastName)) doorSeg = seg;
        }

        Snapshot s = new Snapshot();
        s.ts = System.currentTimeMillis();
        s.gear = intVals.get("VCU_CURRENT_GEARLEV");
        s.soc = intVals.get("VCU_BMS_SOCDISP");
        s.rangeCur = intVals.get("VCU_DSTBAT_DISP");
        s.chargeStatus = intVals.get("VCU_CHARGE_STATUS");
        s.chargeGun = intVals.get("VCU_CHARGE_GUN_STATUS");
        s.accPedal = intVals.get("VCU_ACCPEDAL_SIG");
        s.brkPedal = intVals.get("VCU_BRKPEDAL_ST");
        s.doors = intVals.get("BCM_DOOR");
        s.trunk = intVals.get("BCM_TRUNKAJAR");
        s.bonnet = intVals.get("BCM_BONNET");
        // v2.12：BCM_DOOR 实车为四门 int32Values 数组（非位掩码）——完整数组透传前端合成四门状态
        if (doorSeg != null) {
            Matcher dim = INT32_SEG.matcher(doorSeg);
            if (dim.find()) s.doorsArr = parseIntArray(dim.group(1));
        }
        // 续航标准值：优先 _FLOAT 的 floatValues；VCU_DSTBAT_DISP_CLTC 也可能走 int32
        Float cltcF = floatVals.get("VCU_DSTBAT_DISP_CLTC_FLOAT");
        Integer cltcI = intVals.get("VCU_DSTBAT_DISP_CLTC");
        s.rangeCltc = cltcF != null ? cltcF : (cltcI != null ? cltcI.floatValue() : null);
        s.rangeWltp = floatVals.get("VCU_DSTBAT_DISP_WLTP_FLOAT");
        s.rangeNedc = floatVals.get("VCU_DSTBAT_DISP_NEDC_FLOAT");
        // v1.45：电耗 / 充电电压电流 / 灯光状态（pickFloat：优先 floatValues，其次 int32Values）
        s.consp100m = pickFloat(floatVals, intVals, "VCU_VEHELC_CONSP_100M");
        s.conspAvg = pickFloat(floatVals, intVals, "VCU_VEHELC_CONSP_AVG");
        s.obcVolt = pickFloat(floatVals, intVals, "VCU_OBC_DCVOLT");
        s.obcCur = pickFloat(floatVals, intVals, "VCU_OBC_DCCUR");
        s.dccsCurr = pickFloat(floatVals, intVals, "VCU_DCCCS_CURR");
        s.dccsSumu = pickFloat(floatVals, intVals, "VCU_DCCCS_SUMU");
        s.lturn = pickFloat(floatVals, intVals, "BCM_LTURNLAMP_ST");
        s.rturn = pickFloat(floatVals, intVals, "BCM_RTURNLAMP_ST");
        s.fog = pickFloat(floatVals, intVals, "FOG_LIGHTS_SWITCH_XP");
        s.lowBeam = pickFloat(floatVals, intVals, "BCM_LOW_BEAM");
        s.frontLightGroup = pickFloat(floatVals, intVals, "BCM_FRONT_LIGHT_GROUP_MODE");
        // v2.11：门属性透传——真车 BCM_DOOR 实测仅主驾门状态（bit0），副驾/后排有独立 BCM_DOOR_* 属性时
        // 原样透传给前端自适应合成四门状态；命名未知不在原生层硬编码。
        Map<String, Integer> doorProps = new HashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : intVals.entrySet()) {
            if (e.getKey().startsWith("BCM_DOOR")) doorProps.put(e.getKey(), e.getValue());
        }
        s.doorProps = doorProps.isEmpty() ? null : doorProps;
        // v2.13：CDU_IBCM_*POS 四门位置属性透传（实车确认存在，前端优先用作四门开合判据）。
        // 双通道收集：位置类属性实车可能走 floatValues（角度/百分比形态），int32 兜底，
        // 避免 doorPos 整体为空导致前端回退到位掩码/数组而副驾/后座仍读不到。
        Map<String, Float> doorPos = new HashMap<String, Float>();
        for (String pk : new String[]{"CDU_IBCM_FLPOS", "CDU_IBCM_FRPOS", "CDU_IBCM_RLPOS", "CDU_IBCM_RRPOS"}) {
            Float pf = floatVals.get(pk);
            Integer pi = intVals.get(pk);
            if (pf != null) doorPos.put(pk, pf);
            else if (pi != null) doorPos.put(pk, pi.floatValue());
        }
        s.doorPos = doorPos.isEmpty() ? null : doorPos;

        lastSnapshot = snapshotToJson(s);
        lastSnapshotTs = s.ts;
        pushJs("window.__adbSnapshot=" + lastSnapshot + ";");
    }

    private static void parseSeg(String name, String seg, Map<String, Integer> intVals, Map<String, Float> floatVals) {
        if (seg == null || seg.isEmpty()) return;
        Matcher im = INT32_SEG.matcher(seg);
        if (im.find()) {
            String first = firstToken(im.group(1));
            if (first != null) {
                try {
                    intVals.put(name, Integer.parseInt(first));
                } catch (NumberFormatException ignore) { /* 忽略 */ }
            }
        }
        Matcher fm = FLOAT_SEG.matcher(seg);
        if (fm.find()) {
            String first = firstToken(fm.group(1));
            if (first != null) {
                try {
                    floatVals.put(name, Float.parseFloat(first));
                } catch (NumberFormatException ignore) { /* 忽略 */ }
            }
        }
    }

    /** v1.45：优先 float 值、其次 int 值的取值助手（int32/float 两种上报形态均可解析） */
    private static Float pickFloat(Map<String, Float> floatVals, Map<String, Integer> intVals, String name) {
        Float f = floatVals.get(name);
        if (f != null) return f;
        Integer i = intVals.get(name);
        return i != null ? i.floatValue() : null;
    }

    private static String firstToken(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (c.isEmpty()) return null;
        String[] parts = c.split("[,\\s]+");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    /** v2.12：解析 int32Values 方括号内全部 token（BCM_DOOR 四门数组）；无有效数字返回 null */
    private static int[] parseIntArray(String content) {
        if (content == null) return null;
        String[] parts = content.split("[,\\s]+");
        int[] tmp = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                tmp[n++] = Integer.parseInt(t);
            } catch (NumberFormatException ignore) { /* 忽略单个脏 token */ }
        }
        if (n == 0) return null;
        int[] arr = new int[n];
        System.arraycopy(tmp, 0, arr, 0, n);
        return arr;
    }

    private static String snapshotToJson(Snapshot s) {
        StringBuilder b = new StringBuilder(256);
        b.append('{');
        b.append("\"ts\":").append(s.ts).append(',');
        b.append("\"gear\":").append(nv(s.gear)).append(',');
        b.append("\"soc\":").append(nv(s.soc)).append(',');
        b.append("\"rangeCur\":").append(nv(s.rangeCur)).append(',');
        b.append("\"rangeCltc\":").append(nv(s.rangeCltc)).append(',');
        b.append("\"rangeWltp\":").append(nv(s.rangeWltp)).append(',');
        b.append("\"rangeNedc\":").append(nv(s.rangeNedc)).append(',');
        b.append("\"chargeStatus\":").append(nv(s.chargeStatus)).append(',');
        b.append("\"chargeGun\":").append(nv(s.chargeGun)).append(',');
        b.append("\"accPedal\":").append(nv(s.accPedal)).append(',');
        b.append("\"brkPedal\":").append(nv(s.brkPedal)).append(',');
        b.append("\"doors\":").append(nv(s.doors)).append(',');
        b.append("\"doorsArr\":");
        if (s.doorsArr != null) {
            b.append('[');
            for (int i = 0; i < s.doorsArr.length; i++) {
                if (i > 0) b.append(',');
                b.append(s.doorsArr[i]);
            }
            b.append(']');
        } else {
            b.append("null");
        }
        b.append(',');
        b.append("\"trunk\":").append(nv(s.trunk)).append(',');
        b.append("\"bonnet\":").append(nv(s.bonnet)).append(',');
        b.append("\"consp100m\":").append(nv(s.consp100m)).append(',');
        b.append("\"conspAvg\":").append(nv(s.conspAvg)).append(',');
        b.append("\"obcVolt\":").append(nv(s.obcVolt)).append(',');
        b.append("\"obcCur\":").append(nv(s.obcCur)).append(',');
        b.append("\"dccsCurr\":").append(nv(s.dccsCurr)).append(',');
        b.append("\"dccsSumu\":").append(nv(s.dccsSumu)).append(',');
        b.append("\"lturn\":").append(nv(s.lturn)).append(',');
        b.append("\"rturn\":").append(nv(s.rturn)).append(',');
        b.append("\"fog\":").append(nv(s.fog)).append(',');
        b.append("\"lowBeam\":").append(nv(s.lowBeam)).append(',');
        b.append("\"frontLightGroup\":").append(nv(s.frontLightGroup)).append(',');
        b.append("\"doorProps\":{");
        if (s.doorProps != null) {
            boolean first = true;
            for (Map.Entry<String, Integer> e : s.doorProps.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
        }
        b.append('}');
        b.append(',');
        b.append("\"doorPos\":{");
        if (s.doorPos != null) {
            // v2.13：固定输出四键（缺失为 null），前端/参数页可区分“未抓到”与“抓到为 0”
            boolean first = true;
            for (String pk : new String[]{"CDU_IBCM_FLPOS", "CDU_IBCM_FRPOS", "CDU_IBCM_RLPOS", "CDU_IBCM_RRPOS"}) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(pk).append("\":");
                Float pv = s.doorPos.get(pk);
                b.append(pv == null ? "null" : String.valueOf(pv));
            }
        }
        b.append('}');
        b.append('}');
        return b.toString();
    }

    private static String nv(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    // ---------- 回传前端 ----------

    private void pushStatus(boolean conn, String host, int port) {
        final String js = "window.__adbStatus={connected:" + conn
                + ",host:" + jsStr(host)
                + ",port:" + port
                + ",error:" + (lastError == null ? "null" : jsStr(lastError))
                + ",ts:" + System.currentTimeMillis() + "};";
        pushJs(js);
    }

    private void pushJs(final String js) {
        final WebView wv = webView;
        if (wv == null || activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    wv.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.w(TAG, "pushJs failed", e);
                }
            }
        });
    }

    private static String jsStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void closeQuietly() {
        try {
            if (sock != null) sock.close();
        } catch (Exception ignore) { /* 忽略 */ }
        sock = null;
        in = null;
        out = null;
    }
}
