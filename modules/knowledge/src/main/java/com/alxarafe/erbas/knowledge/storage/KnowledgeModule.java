package com.alxarafe.erbas.knowledge.storage;

import java.util.List;
import com.alxarafe.erbas.modularity.ErbasModule;
import com.alxarafe.erbas.modularity.ModuleApi;
import com.alxarafe.erbas.modularity.ModuleDescriptor;

public final class KnowledgeModule implements ErbasModule {
    public static final String ID = "erbas.knowledge";
    @Override public ModuleDescriptor descriptor() { return new ModuleDescriptor(ID, ModuleApi.CURRENT_VERSION, List.of()); }
    @Override public void initialize() { }
}
