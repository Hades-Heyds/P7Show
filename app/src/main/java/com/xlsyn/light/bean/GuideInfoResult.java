package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class GuideInfoResult {
    public int guideState;
    public int status;
    public String ttsMsg;

    public GuideInfoResult() {
        this.status = 0;
        this.guideState = 0;
        this.ttsMsg = "";
    }

    public GuideInfoResult(int i2, int i3, String str) {
        this.status = i2;
        this.guideState = i3;
        this.ttsMsg = str;
    }
}
