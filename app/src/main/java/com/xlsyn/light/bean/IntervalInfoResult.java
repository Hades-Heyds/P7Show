package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class IntervalInfoResult {
    public int intervalLen;
    public int spdLimit;
    public int status;
    public int trajLen;
    public int trajSecs;

    public IntervalInfoResult() {
        this.status = 0;
        this.spdLimit = 0;
        this.intervalLen = 0;
        this.trajLen = 0;
        this.trajSecs = 0;
    }

    public IntervalInfoResult(int i2, int i3, int i4, int i5, int i6) {
        this.status = i2;
        this.spdLimit = i3;
        this.intervalLen = i4;
        this.trajLen = i5;
        this.trajSecs = i6;
    }
}
