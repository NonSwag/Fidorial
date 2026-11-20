package fr.fidorial.registrygen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import fr.fidorial.registrygen.model.RegistrySync;
import fr.fidorial.registrygen.model.RegistryTypeDefinition;
import fr.fidorial.registrygen.model.SupportedRegistries;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to generate for one module: the {@code "extensions": { "registry-generator": { ... } }} block of its
 * {@code project.jsonc} (was the {@code fidorialRegistryGenerator { }} block of its Gradle build), with the
 * defaults the Gradle plugin had. Paths are relative to the module.
 *
 * @param module                      the module directory
 * @param minecraftVersion            {@code "minecraftVersion"}, else fidorial-server's {@code MINECRAFT_VERSION_ID} define
 * @param workingDirectory            downloads and Mojang's reports; {@code .cache/registry-generator}
 * @param generatedSourcesDirectory   {@code src/generated/java}
 * @param generatedPackage            {@code fr.fidorial.registry}
 * @param registryDataPackage         {@code <generatedPackage>.data}
 * @param registryKeysPackage         {@code <generatedPackage>.keys}
 * @param registries                  registry identifier to Java type name; every supported registry when empty
 * @param frozenRegistries            every frozen registry by default
 * @param protocolIdRegistries        registries whose keys get their network ids
 * @param apiRegistryPackage          {@code fr.fidorial.registry}
 * @param registryValuesPackage       generates the registry values when set
 * @param registriesDatasetDirectory  writes the registry dataset when set
 * @param generatePacketCatalogs      {@code false}
 * @param generateBlockStates         {@code false}
 * @param generateRegistryKey         {@code true}
 * @param dataGeneratorArguments      {@code ["--reports"]}
 * @param prismarineMinecraftData     a {@code data/pc} version of minecraft-data; the Prismarine-based steps run when set
 * @param prismarineDataRepository    {@code PrismarineJS/minecraft-data}
 * @param prismarineDataRef           {@code master}
 * @param blockPackage                {@code <generatedPackage>.world.block}
 * @param blockTypeKeysPackage        {@code <registryKeysPackage>}
 * @param blockEntityTypeKeysPackage  {@code <registryKeysPackage>}
 * @param itemKeysPackage             {@code <registryKeysPackage>}
 * @param entityPackage               {@code fr.euphyllia.fidorial.server.entity}
 */
public record Settings(
        Path module,
        String minecraftVersion,
        Path workingDirectory,
        Path generatedSourcesDirectory,
        String generatedPackage,
        String registryDataPackage,
        String registryKeysPackage,
        Map<String, String> registries,
        List<String> frozenRegistries,
        List<String> protocolIdRegistries,
        String apiRegistryPackage,
        String registryValuesPackage,
        Path registriesDatasetDirectory,
        boolean generatePacketCatalogs,
        boolean generateBlockStates,
        boolean generateRegistryKey,
        List<String> dataGeneratorArguments,
        String prismarineMinecraftData,
        String prismarineDataRepository,
        String prismarineDataRef,
        String blockPackage,
        String blockTypeKeysPackage,
        String blockEntityTypeKeysPackage,
        String itemKeysPackage,
        String entityPackage,
        Path recipesDataset,
        Path lootTablesDataset,
        Path blockMiningDataset,
        Path blockSupportDataset,
        Path blockCollisionDataset,
        Path blockItemsDataset) {

    public static final String EXTENSION = "registry-generator";

    /** Reads the settings of the module in {@code module}. */
    public static Settings read(final Path module) throws IOException {
        final Path manifest = module.resolve("project.jsonc");
        final JsonObject root = parseJsonc(manifest);
        final JsonObject extensions = root.has("extensions") ? root.getAsJsonObject("extensions") : new JsonObject();
        if (!extensions.has(EXTENSION)) {
            throw new IllegalArgumentException(manifest + " has no \"extensions\": { \"" + EXTENSION + "\": { ... } }");
        }
        final JsonObject c = extensions.getAsJsonObject(EXTENSION);

        final String generatedPackage = string(c, "generatedPackage", "fr.fidorial.registry");
        final String keysPackage = string(c, "registryKeysPackage", generatedPackage + ".keys");
        final List<String> allFrozen = SupportedRegistries.ALL.stream()
                .filter(type -> type.sync() == RegistrySync.FROZEN)
                .map(RegistryTypeDefinition::identifier)
                .sorted()
                .toList();

        return new Settings(
                module,
                c.has("minecraftVersion") ? c.get("minecraftVersion").getAsString() : serverMinecraftVersion(module),
                module.resolve(string(c, "workingDirectory", ".cache/registry-generator")),
                module.resolve(string(c, "generatedSourcesDirectory", "src/generated/java")),
                generatedPackage,
                string(c, "registryDataPackage", generatedPackage + ".data"),
                keysPackage,
                map(c, "registries"),
                c.has("frozenRegistries") ? list(c, "frozenRegistries") : allFrozen,
                list(c, "protocolIdRegistries"),
                string(c, "apiRegistryPackage", "fr.fidorial.registry"),
                string(c, "registryValuesPackage", null),
                path(module, c, "registriesDatasetDirectory"),
                bool(c, "generatePacketCatalogs", false),
                bool(c, "generateBlockStates", false),
                bool(c, "generateRegistryKey", true),
                c.has("dataGeneratorArguments") ? list(c, "dataGeneratorArguments") : List.of("--reports"),
                string(c, "prismarineMinecraftData", null),
                string(c, "prismarineDataRepository", "PrismarineJS/minecraft-data"),
                string(c, "prismarineDataRef", "master"),
                string(c, "blockPackage", generatedPackage + ".world.block"),
                string(c, "blockTypeKeysPackage", keysPackage),
                string(c, "blockEntityTypeKeysPackage", keysPackage),
                string(c, "itemKeysPackage", keysPackage),
                string(c, "entityPackage", "fr.euphyllia.fidorial.server.entity"),
                path(module, c, "recipesDataset"),
                path(module, c, "lootTablesDataset"),
                path(module, c, "blockMiningDataset"),
                path(module, c, "blockSupportDataset"),
                path(module, c, "blockCollisionDataset"),
                path(module, c, "blockItemsDataset"));
    }

    static JsonObject parseJsonc(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final JsonReader json = new JsonReader(reader);
            json.setStrictness(Strictness.LENIENT); // comments and trailing commas
            return JsonParser.parseReader(json).getAsJsonObject();
        }
    }

    /** The single place the Minecraft version is written: fidorial-server's {@code MINECRAFT_VERSION_ID} define. */
    private static String serverMinecraftVersion(final Path module) throws IOException {
        final Path server = module.toAbsolutePath().normalize().resolveSibling("fidorial-server").resolve("project.jsonc");
        final Matcher m = Pattern.compile("\"MINECRAFT_VERSION_ID\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(Files.readString(server));
        if (!m.find()) {
            throw new IllegalStateException(server + " defines no MINECRAFT_VERSION_ID; set \"minecraftVersion\"");
        }
        return m.group(1);
    }

    private static String string(final JsonObject c, final String key, final String fallback) {
        return c.has(key) ? c.get(key).getAsString() : fallback;
    }

    private static boolean bool(final JsonObject c, final String key, final boolean fallback) {
        return c.has(key) ? c.get(key).getAsBoolean() : fallback;
    }

    private static Path path(final Path module, final JsonObject c, final String key) {
        return c.has(key) ? module.resolve(c.get(key).getAsString()) : null;
    }

    private static List<String> list(final JsonObject c, final String key) {
        final List<String> out = new ArrayList<>();
        if (c.has(key)) {
            final JsonArray array = c.getAsJsonArray(key);
            for (final JsonElement e : array) {
                out.add(e.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> map(final JsonObject c, final String key) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (c.has(key)) {
            for (final Map.Entry<String, JsonElement> e : c.getAsJsonObject(key).entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }
}
