package com.xlsyn.light;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/* JADX INFO: loaded from: classes.dex */
public class NativeUtils {
    public static String getApkFilePath(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).sourceDir;
        } catch (PackageManager.NameNotFoundException e2) {
            e2.printStackTrace();
            return null;
        }
    }

    public static String getAppDataDir(Context context) {
        return context.getApplicationInfo().dataDir;
    }

    /* JADX WARN: Code duplicated, block: B:19:0x0032  */
    /* JADX WARN: Code duplicated, block: B:24:0x0048 A[PHI: r2
  0x0048: PHI (r2v2 java.lang.String) = (r2v1 java.lang.String), (r2v5 java.lang.String) binds: [B:20:0x0033, B:22:0x0045] A[DONT_GENERATE, DONT_INLINE]] */
    /* JADX WARN: Code restructure failed: missing block: B:14:0x0028, code lost:
    
        r2 = r2.getDeviceId();
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static String getDeviceUid(Context context) {
        String string = null;
        String str = null;
        String serial = null;
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService("phone");
            if (telephonyManager == null) {
                string = null;
            } else {
                int i2 = Build.VERSION.SDK_INT;
                if (i2 >= 29) {
                    if (context.checkSelfPermission("android.permission.READ_PHONE_STATE") == 0) {
                        string = telephonyManager.getImei();
                    } else {
                        string = null;
                    }
                } else if (i2 >= 23) {
                    string = null;
                } else {
                    string = null;
                }
            }
        } catch (Exception unused) {
        }
        if (string == null) {
            string = Settings.Secure.getString(context.getContentResolver(), "android_id");
            str = "9774d56d682e549c".equals(string) ? null : string;
        }
        if (str == null) {
            try {
                if (Build.VERSION.SDK_INT < 26) {
                    serial = Build.SERIAL;
                } else if (context.checkSelfPermission("android.permission.READ_PHONE_STATE") == 0) {
                    serial = Build.getSerial();
                }
                str = serial;
            } catch (SecurityException unused2) {
            }
        }
        if (str != null) {
            return str;
        }
        return String.valueOf((Build.BOARD + Build.BRAND + Build.DEVICE + Build.HARDWARE + Build.MODEL + Build.PRODUCT + Build.MANUFACTURER).hashCode());
    }
}
