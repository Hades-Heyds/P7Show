package com.xlsyn.light.bean;

/* JADX INFO: loaded from: classes.dex */
public class CrossTli {
    public int dist;
    public TrafficEvent[] events;
    public int[] guidelane;
    public int inAngle;
    public int lat;
    public int lightType;
    public int lon;
    public LightPhase[] phases;
    public int spdLimit;
    public int userPref;

    public CrossTli() {
    }

    public CrossTli(int i2, int i3, int i4, int i5, int i6, int i7, int i8, LightPhase[] lightPhaseArr, int[] iArr, TrafficEvent[] trafficEventArr) {
        this.lon = i2;
        this.lat = i3;
        this.inAngle = i4;
        this.lightType = i5;
        this.dist = i6;
        this.spdLimit = i7;
        this.userPref = i8;
        this.phases = lightPhaseArr;
        this.guidelane = iArr;
        this.events = trafficEventArr;
    }
}
