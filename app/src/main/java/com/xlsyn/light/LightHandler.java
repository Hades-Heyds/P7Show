package com.xlsyn.light;

import com.xlsyn.light.bean.GWInfoResult;
import com.xlsyn.light.bean.GuideInfoResult;
import com.xlsyn.light.bean.IntervalInfoResult;
import com.xlsyn.light.bean.LLPoint;
import com.xlsyn.light.bean.LightInfoResult;

/* JADX INFO: loaded from: classes.dex */
public class LightHandler {
    private static final String TAG = "LightHandler";
    private static LightHandler lightHandler;

    static {
        System.loadLibrary("light");
    }

    public static synchronized LightHandler getInstance() {
        if (lightHandler == null) {
            lightHandler = new LightHandler();
        }
        return lightHandler;
    }

    public native synchronized void nativeDestroy();

    public native synchronized GWInfoResult nativeGetGWInfo();

    public native synchronized GuideInfoResult nativeGetGuideInfo();

    public native synchronized IntervalInfoResult nativeGetIntervalInfo();

    public native synchronized LightInfoResult nativeGetLightInfo();

    public native synchronized String nativeGetVersion();

    public native synchronized boolean nativeInit(String str, long j2, int i2, String str2);

    public native synchronized boolean nativeSetJsonCfg(String str);

    public native synchronized boolean nativeSetRoute(LLPoint[] lLPointArr, int i2);

    public native synchronized boolean nativeUpdateGeoPos(long j2, int i2, int i3, int i4, int i5, int i6, int i7);
}
