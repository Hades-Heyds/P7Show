package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class LightPhase {
    public long curEndTime;
    public int curState;
    public int flowDir;
    public long nextEndTime;
    public int nextState;

    public LightPhase() {
    }

    public LightPhase(int i2, int i3, int i4, long j2, long j3) {
        this.flowDir = i2;
        this.curState = i3;
        this.nextState = i4;
        this.curEndTime = j2;
        this.nextEndTime = j3;
    }
}
