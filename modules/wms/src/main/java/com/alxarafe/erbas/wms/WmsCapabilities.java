package com.alxarafe.erbas.wms;

import java.util.List;
import java.util.Set;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.capabilities.CapabilityPermissionPolicy;
import com.alxarafe.erbas.capabilities.CapabilityProvider;

/** Published v0.9 declarations and its existing administrator bridge only. */
public final class WmsCapabilities implements CapabilityProvider, CapabilityPermissionPolicy {
    public static final String UOMS_READ = "wms.uoms.read";
    public static final String STORAGE_ATTRIBUTES_READ = "wms.storage-attributes.read";
    public static final String ITEM_FAMILIES_READ = "wms.item-families.read";
    public static final String UOMS_LIST = "wms.uoms.list";
    public static final String STORAGE_ATTRIBUTES_LIST = "wms.storage-attributes.list";
    public static final String ITEM_FAMILIES_LIST = "wms.item-families.list";
    private static final Set<String> PERMISSIONS = Set.of(UOMS_READ, STORAGE_ATTRIBUTES_READ, ITEM_FAMILIES_READ);
    private static final String VERSION = "1.0.0";
    private static final Capability.SchemaReference INPUT = reference("CapabilityListInput");
    private static final List<Capability> DECLARATIONS = List.of(
            new Capability(UOMS_LIST, WmsModule.ID, VERSION, "List units of measure",
                    "GET /api/wms/uoms with optional offset and limit; returns the existing paged catalogue.",
                    Capability.READ, INPUT, reference("WmsUomCollection"), List.of(UOMS_READ)),
            new Capability(STORAGE_ATTRIBUTES_LIST, WmsModule.ID, VERSION, "List storage attributes",
                    "GET /api/wms/storage-attributes with optional offset and limit; returns the existing paged catalogue.",
                    Capability.READ, INPUT, reference("WmsStorageAttributeCollection"), List.of(STORAGE_ATTRIBUTES_READ)),
            new Capability(ITEM_FAMILIES_LIST, WmsModule.ID, VERSION, "List item families",
                    "GET /api/wms/item-families with optional offset and limit; returns the existing paged catalogue.",
                    Capability.READ, INPUT, reference("WmsItemFamilyCollection"), List.of(ITEM_FAMILIES_READ)));
    @Override public List<Capability> capabilities() { return DECLARATIONS; }
    @Override public boolean granted(UserIdentity user, String permission) {
        return user != null && user.enabled() && user.admin() && PERMISSIONS.contains(permission);
    }
    private static Capability.SchemaReference reference(String component) {
        return new Capability.SchemaReference(Capability.SchemaReference.PREFIX + component);
    }
}
