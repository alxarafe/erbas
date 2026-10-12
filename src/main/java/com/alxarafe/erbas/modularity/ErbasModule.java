package com.alxarafe.erbas.modularity;

public interface ErbasModule {
    ModuleDescriptor descriptor();

    void initialize();
}
