package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class GWInfoResult {
    public int crossCnt;
    public int crossIdx;
    public int greenCnt;
    public int gwId;
    public String gwName;
    public String gwOrgName;
    public int maxSpeed;
    public int minSpeed;
    public int status;

    public GWInfoResult() {
        this.status = 0;
        this.gwId = 0;
        this.crossCnt = 0;
        this.crossIdx = 0;
        this.minSpeed = 0;
        this.maxSpeed = 0;
        this.greenCnt = 0;
        this.gwName = "";
        this.gwOrgName = "";
    }

    public GWInfoResult(int i2, int i3, int i4, int i5, int i6, int i7, int i8, String str, String str2) {
        this.status = i2;
        this.gwId = i3;
        this.crossCnt = i4;
        this.crossIdx = i5;
        this.minSpeed = i6;
        this.maxSpeed = i7;
        this.greenCnt = i8;
        this.gwName = str;
        this.gwOrgName = str2;
    }
}
