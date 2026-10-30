package com.alxarafe.erbas.ai;

import java.util.List;
import com.alxarafe.erbas.modularity.ErbasModule;
import com.alxarafe.erbas.modularity.ModuleApi;
import com.alxarafe.erbas.modularity.ModuleDescriptor;

public final class AiModule implements ErbasModule {
    public static final String ID = "erbas.ai";
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of());
    @Override public ModuleDescriptor descriptor() { return DESCRIPTOR; }
    @Override public void initialize() { }
}
