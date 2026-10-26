package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class TrafficEvent {
    public int dist;
    public int intervalLen;
    public int lat;
    public int lon;
    public int spdLimit;
    public int type;

    public TrafficEvent() {
    }

    public TrafficEvent(int i2, int i3, int i4, int i5, int i6, int i7) {
        this.lon = i2;
        this.lat = i3;
        this.type = i4;
        this.dist = i5;
        this.spdLimit = i6;
        this.intervalLen = i7;
    }
}
