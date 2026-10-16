package com.alxarafe.erbas.modularity;

import java.util.Collection;

/** Supplies modules available during this startup, independently of their origin. */
@FunctionalInterface
public interface ModuleSource {
    Collection<? extends ErbasModule> modules();
}
