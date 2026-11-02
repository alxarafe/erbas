package com.alxarafe.erbas.assets;

import java.util.List;
import com.alxarafe.erbas.modularity.*;

public final class AssetsModule implements ErbasModule {
    public static final String ID = "erbas.assets";
    @Override public ModuleDescriptor descriptor() { return new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of()); }
    @Override public void initialize() { }
}
