package com.alxarafe.erbas;

import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Examines the packaged reactor artifacts built into the validation image. */
class ArchitectureArtifactIT {
    private static final Path ROOT = Path.of(System.getProperty("erbas.project.root"));
    private static final String VERSION = System.getProperty("erbas.project.version");
    private static final String CORE_ARTIFACT = "erbas-core";
    private static final String WMS_ARTIFACT = "erbas-wms";
    private static final String AI_ARTIFACT = "erbas-ai";
    private static final String HOST_ARTIFACT = "erbas-host";

    @Test
    void librariesContainOnlyTheirOwnedMigrationsAndAreNotExecutable() throws Exception {
        try (var core = jar("erbas-core", CORE_ARTIFACT); var wms = jar("modules/wms", WMS_ARTIFACT);
                var ai = jar("modules/ai", AI_ARTIFACT)) {
            var coreFiles = core.stream().map(entry -> entry.getName()).toList();
            var wmsFiles = wms.stream().map(entry -> entry.getName()).toList();
            assertThat(coreFiles.stream().filter(name -> name.endsWith(".sql")))
                    .hasSize(4).allMatch(name -> name.startsWith("db/core/"));
            assertThat(coreFiles).noneMatch(name -> name.contains("/wms/") || name.contains("/ai/") || name.contains("legacy-v060") || name.contains("legacy-v070"));
            assertThat(wmsFiles).contains("db/wms/V5__wms_uoms.sql", "db/wms/V6__wms_storage_attributes.sql", "db/wms/V7__wms_item_families.sql");
            var aiFiles = ai.stream().map(entry -> entry.getName()).toList();
            assertThat(aiFiles).contains("i18n/ai/messages.properties", "i18n/ai/messages_es.properties");
            assertThat(aiFiles).noneMatch(name -> name.contains("/wms/") || name.endsWith(".sql") || name.contains("Tests"));
            for (var library : List.of(core, wms, ai)) {
                assertThat(library.getManifest().getMainAttributes().getValue("Main-Class")).isNull();
                assertThat(library.stream().map(entry -> entry.getName())).noneMatch(name -> name.startsWith("BOOT-INF/"));
            }
        }
    }

    @Test
    void onlyHostIsBootExecutableAndTestFixturesDoNotShip() throws Exception {
        try (var host = jar("erbas-host", HOST_ARTIFACT)) {
            var manifest = host.getManifest().getMainAttributes();
            assertThat(manifest.getValue("Start-Class")).isEqualTo(ErbasApplication.class.getName());
            assertThat(manifest.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            var entries = host.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).contains("BOOT-INF/lib/" + CORE_ARTIFACT + "-" + VERSION + ".jar",
                    "BOOT-INF/lib/" + WMS_ARTIFACT + "-" + VERSION + ".jar",
                    "BOOT-INF/lib/" + AI_ARTIFACT + "-" + VERSION + ".jar");
            assertThat(entries).noneMatch(name -> name.contains("legacy-v060") || name.contains("legacy-v070") || name.startsWith("BOOT-INF/classes/i18n/probe/"));
        }
    }

    private static JarFile jar(String module, String artifact) throws Exception {
        return new JarFile(ROOT.resolve(module + "/target/" + artifact + "-" + VERSION + ".jar").toFile());
    }
}
