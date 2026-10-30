package com.alxarafe.erbas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTests {
    private static final Path ROOT = Path.of(System.getProperty("erbas.project.root"));
    private static final Path CORE = ROOT.resolve("erbas-core");
    private static final Path WMS = ROOT.resolve("modules/wms");
    private static final Path AI = ROOT.resolve("modules/ai");
    private static final Path HOST = ROOT.resolve("erbas-host");
    private static final String JAVA_SOURCES = "src/main/java";
    private static final String RESOURCES = "src/main/resources";
    private static final String WMS_PACKAGE = "com.alxarafe.erbas.wms";
    private static final String WMS_COMPOSITION = WMS_PACKAGE + ".infrastructure.WmsConfiguration";
    private static final String AI_PACKAGE = "com.alxarafe.erbas.ai";
    private static final String AI_COMPOSITION = AI_PACKAGE + ".infrastructure.AiConfiguration";
    private static final String BOOT_PLUGIN = "spring-boot-maven-plugin";
    private static final String LEGACY_FIXTURES = "src/test/resources/db/legacy-v060";
    private static final Set<String> CORE_MIGRATIONS = Set.of("V1__create_persistence_marker.sql",
            "V2__create_authentication_schema.sql", "V3__user_administrator.sql", "V4__module_states.sql");
    private static final String WMS_MIGRATION = "V5__wms_uoms.sql";
    private static final List<String> FORBIDDEN_WMS_INNER_DEPENDENCIES = List.of("org.springframework.", "org.flywaydb.",
            "java.sql.", "javax.sql.", "java.net.", WMS_PACKAGE + ".http.", WMS_PACKAGE + ".infrastructure.",
            "com.alxarafe.erbas.core.", "com.alxarafe.erbas.ErbasApplication");

    @Test
    void mavenDependencyDirectionIsHostToCoreAndWmsAndWmsToCore() throws Exception {
        assertThat(dependencies(ROOT)).doesNotContain("erbas-core", "erbas-wms", "erbas-ai", "erbas-host");
        assertThat(dependencies(CORE)).doesNotContain("erbas-wms", "erbas-ai", "erbas-host");
        assertThat(dependencies(WMS)).contains("erbas-core").doesNotContain("erbas-ai", "erbas-host");
        assertThat(dependencies(AI)).containsExactly("erbas-core");
        assertThat(dependencies(HOST)).contains("erbas-core", "erbas-wms", "erbas-ai");
    }

    @Test
    void coreProductionSourcesAndResourcesHaveNoWmsBusinessKnowledge() throws Exception {
        for (var file : files(CORE.resolve("src/main"))) {
            assertThat(Files.readString(file)).as(file.toString()).doesNotContain(WMS_PACKAGE,
                    "WmsModule", "/api/wms", "wms_uoms", "ItemFamily", "wms_item_families", "wms_item_family", "wms_storage_attribute",
                    AI_PACKAGE, "/api/ai", "AiModule");
        }
    }

    @Test
    void hostExplicitlyImportsOnlyTheWmsCompositionBoundary() throws Exception {
        var source = Files.readString(HOST.resolve(JAVA_SOURCES + "/com/alxarafe/erbas/ErbasApplication.java"));
        assertThat(source).contains("@Import({CoreConfiguration.class, WmsConfiguration.class, AiConfiguration.class})")
                .doesNotContain("@SpringBootApplication", "@ComponentScan");
        for (var file : files(HOST.resolve(JAVA_SOURCES))) {
            // Include qualified references as well as imports.
            var references = Pattern.compile(Pattern.quote(WMS_PACKAGE) + "(?:\\.[\\w$]+)+")
                    .matcher(Files.readString(file));
            while (references.find()) assertThat(references.group()).isEqualTo(WMS_COMPOSITION);
            var aiReferences = Pattern.compile(Pattern.quote(AI_PACKAGE) + "(?:\\.[\\w$]+)+").matcher(Files.readString(file));
            while (aiReferences.find()) assertThat(aiReferences.group()).isEqualTo(AI_COMPOSITION);
        }
    }

    @Test
    void aiUsesCoreGovernanceWithoutBusinessInternalsOrExternalTools() throws Exception {
        for (var file : files(AI.resolve("src/main"))) {
            assertThat(Files.readString(file)).as(file.toString()).doesNotContain(WMS_PACKAGE,
                    "UomCatalogue", "StorageAttributeCatalogue", "ItemFamilyCatalogue", "JdbcTemplate",
                    "RestTemplate", "WebClient", "java.net.", "java.sql.", "org.springframework.jdbc",
                    "org.springframework.ai", "com.openai", "io.modelcontextprotocol");
        }
        var orchestration = Files.readString(AI.resolve(JAVA_SOURCES + "/com/alxarafe/erbas/ai/AssistantInvocation.java"));
        assertThat(orchestration).contains("capabilities.invoke(", "discovery.effective(", "primitives.invoke(", "primitives.effective(");
    }

    @Test
    void primitiveRouterIsNeutralAndNoProductiveFixtureOrHttpTransportIsInstalled() throws Exception {
        var router = Files.readString(CORE.resolve(JAVA_SOURCES + "/com/alxarafe/erbas/read/ReadPrimitiveRouter.java"));
        assertThat(router).contains("users.apply(").doesNotContain("java.net.", "RestTemplate", "WebClient", "@RestController");
        for (var module : List.of(CORE, WMS, AI)) {
            for (var file : files(module.resolve("src/main"))) {
                assertThat(Files.readString(file)).doesNotContain("wms.inventory.list", "fixture.units.read");
            }
        }
    }

    @Test
    void wmsDomainAndApplicationRemainFrameworkAndAdapterIndependent() throws Exception {
        for (var layer : List.of("domain", "application")) {
            for (var file : files(WMS.resolve(JAVA_SOURCES + "/com/alxarafe/erbas/wms/" + layer))) {
                var source = Files.readString(file);
                for (var forbidden : FORBIDDEN_WMS_INNER_DEPENDENCIES) {
                    assertThat(source).as(file.toString()).doesNotContain(forbidden);
                }
            }
        }
    }

    @Test
    void migrationAndExecutableOwnershipFollowTheirPhysicalModules() throws Exception {
        assertThat(files(CORE.resolve(RESOURCES + "/db/core")).stream().map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrderElementsOf(CORE_MIGRATIONS);
        assertThat(files(CORE.resolve(RESOURCES)).stream().anyMatch(path -> path.getFileName().toString().equals(WMS_MIGRATION)))
                .isFalse();
        assertThat(WMS.resolve(RESOURCES + "/db/wms/" + WMS_MIGRATION)).isRegularFile();
        assertThat(Files.readString(HOST.resolve(RESOURCES + "/application.properties")))
                .contains("spring.flyway.locations=classpath:db/core,classpath:db/wms")
                .doesNotContain("spring.flyway.validate-on-migrate=false");
        for (var library : List.of(ROOT, CORE, WMS, AI)) {
            assertThat(Files.readString(library.resolve("pom.xml"))).doesNotContain(BOOT_PLUGIN);
        }
        assertThat(Files.readString(HOST.resolve("pom.xml"))).contains(BOOT_PLUGIN);
    }

    @Test
    void legacyMigrationFixturesAndOwnedSqlAreIdenticalToTheApprovedBase() throws Exception {
        for (var entry : LEGACY_SHA256.entrySet()) {
            var legacy = Files.readAllBytes(HOST.resolve(LEGACY_FIXTURES + "/" + entry.getKey()));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacy)))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
            var owner = CORE_MIGRATIONS.contains(entry.getKey()) ? CORE.resolve(RESOURCES + "/db/core")
                    : WMS.resolve(RESOURCES + "/db/wms");
            assertThat(Files.readAllBytes(owner.resolve(entry.getKey()))).isEqualTo(legacy);
        }
    }

    @Test
    void storageMigrationFixtureIsPinnedToPublishedV070() throws Exception {
        var fixture = Files.readAllBytes(HOST.resolve("src/test/resources/db/legacy-v070/V6__wms_storage_attributes.sql"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)))
                .isEqualTo("e1c24def95853e1a9d1c213d9fe0e5b590dd5daf88c14e6d1dc084a2b132c025");
        assertThat(Files.readAllBytes(WMS.resolve(RESOURCES + "/db/wms/V6__wms_storage_attributes.sql"))).isEqualTo(fixture);
    }

    private static List<Path> files(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static List<String> dependencies(Path module) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(module.resolve("pom.xml").toFile());
        var children = document.getDocumentElement().getChildNodes();
        var result = new java.util.ArrayList<String>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && element.getTagName().equals("dependencies")) {
                var artifacts = element.getElementsByTagName("artifactId");
                for (int j = 0; j < artifacts.getLength(); j++) result.add(artifacts.item(j).getTextContent());
            }
        }
        return result;
    }

    // SHA-256 of the five SQL resources at 4af73d9bf49294f0668ab0849049c2eb5a47c699.
    private static final Map<String, String> LEGACY_SHA256 = Map.ofEntries(
        Map.entry("V1__create_persistence_marker.sql", "51f2638a01de91bfd3a983f8c7f68917feabf90a77c35f4b5b785753dbb4b228"),
        Map.entry("V2__create_authentication_schema.sql", "ee91f56758e1275417d5912ec02418afd79578b773a7e70a29bde6defedc0b37"),
        Map.entry("V3__user_administrator.sql", "0fbedb39ec0863bc2b45cc714f9cabfac960893b4921ea3fd77b05ff4d6ec7aa"),
        Map.entry("V4__module_states.sql", "2bfd8f862828c87bb0b6b6b65fc62df5fbba9fc939063ef9733652f612b25c07"),
        Map.entry("V5__wms_uoms.sql", "2287a92432d331ddcad2b9a8027d11e3dcb7bca15751edbed5494b465e2da244")
    );
}
