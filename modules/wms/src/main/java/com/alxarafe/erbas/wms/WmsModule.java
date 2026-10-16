package com.alxarafe.erbas.wms;

import java.util.List;

import com.alxarafe.erbas.modularity.ErbasModule;
import com.alxarafe.erbas.modularity.ModuleApi;
import com.alxarafe.erbas.modularity.ModuleDescriptor;

public final class WmsModule implements ErbasModule {
    public static final String ID = "erbas.wms";
    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of());

    @Override public ModuleDescriptor descriptor() { return DESCRIPTOR; }
    @Override public void initialize() { }
}
