package com.alxarafe.erbas.assets.domain;

public enum AssetLifecycle {
    DRAFT, READY, RETIRED;

    public boolean mutable() { return this != RETIRED; }
    public boolean deletable() { return this == DRAFT; }
    public boolean canComplete() { return this == DRAFT; }
    public boolean canRetire() { return this == READY; }
}
