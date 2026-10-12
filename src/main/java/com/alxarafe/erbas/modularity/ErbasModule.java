package com.alxarafe.erbas.modularity;

import java.util.List;

/** A Spring-constructed module with stable identity and dependency metadata. */
public interface ErbasModule {
    String id();

    List<String> dependencies();

    void initialize();
}
