package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class LightInfoResult {
    public CrossTli[] crossTlis;
    public long lastReqTime;
    public int status;

    public LightInfoResult() {
        this.status = 0;
        this.lastReqTime = 0L;
    }

    public LightInfoResult(int i2, long j2, CrossTli[] crossTliArr) {
        this.status = i2;
        this.lastReqTime = j2;
        this.crossTlis = crossTliArr;
    }
}
