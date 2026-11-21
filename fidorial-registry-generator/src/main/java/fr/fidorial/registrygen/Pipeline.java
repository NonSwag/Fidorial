package fr.fidorial.registrygen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.fidorial.registrygen.download.ServerJarURLGrabber;
import fr.fidorial.registrygen.generate.BlockCollisionDatasetGenerator;
import fr.fidorial.registrygen.generate.BlockItemsDatasetGenerator;
import fr.fidorial.registrygen.generate.BlockMiningDatasetGenerator;
import fr.fidorial.registrygen.generate.BlockSupportDatasetGenerator;
import fr.fidorial.registrygen.generate.LootTableDatasetGenerator;
import fr.fidorial.registrygen.generate.RecipeDatasetGenerator;
import fr.fidorial.registrygen.generate.RegistryGenerator;
import fr.fidorial.registrygen.model.RegistryTypeDefinition;
import fr.fidorial.registrygen.model.ServerDownload;
import fr.fidorial.registrygen.model.SupportedRegistries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The generation pipeline of one module (was the Gradle plugin's {@code generateAll} task and the tasks it
 * depended on): download the Minecraft server and, when configured, Prismarine's minecraft-data; run
 * Mojang's data generator; then generate the Java sources and datasets the settings ask for.
 */
public final class Pipeline {

    private static final String CONTENTS_URL_TEMPLATE = "https://api.github.com/repos/%s/contents/data/pc/%s?ref=%s";

    private final Settings settings;
    private final Path minecraft;
    private final Path reports;
    private final Path vanillaData;
    private final Path itemComponents;
    private final Path staging;
    private Path prismarine;

    public Pipeline(final Settings settings) {
        this.settings = settings;
        this.minecraft = settings.workingDirectory().resolve("minecraft").resolve(settings.minecraftVersion());
        this.reports = minecraft.resolve("data/generated/reports");
        this.vanillaData = minecraft.resolve("data/generated/data");
        this.itemComponents = reports.resolve("minecraft/components/item");
        this.staging = settings.workingDirectory().resolve("sources");
    }

    public void run() throws IOException, InterruptedException {
        final Path serverJar = downloadServer();
        generateReports(serverJar);
        if (settings.prismarineMinecraftData() != null) {
            prismarine = downloadPrismarineData();
        }

        // Java sources go to a staging directory first, and replace the module's generated folder only once
        // everything was generated.
        deleteTree(staging);
        Files.createDirectories(staging);
        final RegistryGenerator generator = new RegistryGenerator();

        log("Generating registries");
        final Path dataset = settings.registriesDatasetDirectory();
        if (!Files.isDirectory(vanillaData)) {
            log("No vanilla data directory (add \"--server\" to dataGeneratorArguments to also generate registry tags)"
                    + " - every registry in the dataset will have empty tags.");
        }
        final List<RegistryTypeDefinition> types = settings.registries().isEmpty()
                ? SupportedRegistries.ALL
                : settings.registries().entrySet().stream()
                  .map(e -> RegistryTypeDefinition.parse(e.getKey(), e.getValue()))
                  .toList();
        generator.generate(reports.resolve("registries.json"), existing(vanillaData), staging, dataset, types,
                Set.copyOf(settings.protocolIdRegistries()), settings.generatedPackage(), settings.registryDataPackage(),
                settings.registryKeysPackage(), settings.generateRegistryKey());

        if (settings.generatePacketCatalogs()) {
            log("Generating packet catalogs");
            generator.generatePackets(reports.resolve("packets.json"), staging);
        }
        if (settings.generateBlockStates()) {
            log("Generating block states");
            generator.generateBlockStates(reports.resolve("blocks.json"),
                    prismarine != null ? prismarine.resolve("blocks.json") : null, staging,
                    settings.blockPackage(), settings.registryDataPackage(), settings.blockTypeKeysPackage());
        }
        if (prismarine != null) {
            log("Generating item properties, block entity type blocks, entity types and entity properties");
            generator.generateItemProperties(reports.resolve("registries.json"), prismarine.resolve("items.json"),
                    itemComponentsReport(), staging, settings.registryDataPackage(), settings.itemKeysPackage());
            generator.generateBlockEntityTypeBlocks(reports.resolve("registries.json"),
                    prismarine.resolve("blockEntities.json"), staging, settings.registryDataPackage(),
                    settings.blockTypeKeysPackage(), settings.blockEntityTypeKeysPackage());
            generator.generateEntityTypes(reports.resolve("registries.json"), prismarine.resolve("entities.json"),
                    staging, settings.entityPackage());
            generator.generateEntityProperties(reports.resolve("registries.json"), prismarine.resolve("entities.json"),
                    staging, settings.registryDataPackage());
        }
        if (!settings.frozenRegistries().isEmpty()) {
            log("Generating frozen registries");
            generator.generateFrozenRegistries(reports.resolve("registries.json"), existing(vanillaData), staging,
                    settings.registryDataPackage(), settings.frozenRegistries());
        }
        if (settings.registryValuesPackage() != null) {
            log("Generating registry values");
            generator.generateRegistryValues(reports.resolve("registries.json"), existing(vanillaData), staging,
                    settings.registryValuesPackage(), settings.apiRegistryPackage());
        }

        if (settings.recipesDataset() != null) {
            requireVanilla("recipe");
            log("Wrote " + new RecipeDatasetGenerator().generate(vanillaData, settings.recipesDataset())
                    + " vanilla recipes to " + settings.recipesDataset());
        }
        if (settings.lootTablesDataset() != null) {
            requireVanilla("loot_table");
            log("Wrote " + new LootTableDatasetGenerator().generate(vanillaData, settings.lootTablesDataset())
                    + " vanilla loot tables to " + settings.lootTablesDataset());
        }
        if (settings.blockMiningDataset() != null && prismarine != null) {
            log("Wrote the mining data of " + new BlockMiningDatasetGenerator().generate(prismarine.resolve("blocks.json"),
                    itemComponentsReport(), settings.blockMiningDataset()) + " blocks to " + settings.blockMiningDataset());
        }
        if (settings.blockSupportDataset() != null && prismarine != null) {
            log("Wrote the support of " + new BlockSupportDatasetGenerator().generate(
                    prismarine.resolve("blockCollisionShapes.json"), prismarine.resolve("blocks.json"),
                    vanillaData.resolve("minecraft/tags/block"), settings.blockSupportDataset())
                    + " blocks to " + settings.blockSupportDataset());
        }
        if (settings.blockCollisionDataset() != null && prismarine != null) {
            log("Wrote the collision shapes of " + new BlockCollisionDatasetGenerator().generate(
                    prismarine.resolve("blockCollisionShapes.json"), prismarine.resolve("blocks.json"),
                    settings.blockCollisionDataset()) + " blocks to " + settings.blockCollisionDataset());
        }
        if (settings.blockItemsDataset() != null) {
            log("Wrote the blocks of " + new BlockItemsDatasetGenerator().generate(reports.resolve("blocks.json"),
                    itemComponents, vanillaData.resolve("minecraft/loot_table/blocks"),
                    vanillaData.resolve("minecraft/tags/block"), settings.blockItemsDataset())
                    + " items to " + settings.blockItemsDataset());
        }

        publishSources();
    }

    // ------------------------------------------------------------------ downloads and Mojang's reports

    private Path downloadServer() throws IOException {
        final Path jar = minecraft.resolve("jar/server.jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        log("Downloading the Minecraft " + settings.minecraftVersion() + " server");
        final ServerDownload download = ServerJarURLGrabber.fetchServerDownload(settings.minecraftVersion());
        Files.createDirectories(jar.getParent());
        final Path tmp = jar.resolveSibling("server.jar.tmp");
        try (InputStream input = download.url().toURL().openStream()) {
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    private void generateReports(final Path serverJar) throws IOException, InterruptedException {
        final Path dataDirectory = minecraft.resolve("data");
        final Path stamp = dataDirectory.resolve(".arguments");
        final String arguments = String.join(" ", settings.dataGeneratorArguments());
        if (Files.isRegularFile(reports.resolve("registries.json")) && Files.exists(stamp)
                && Files.readString(stamp).equals(arguments)) {
            return; // Mojang's reports only depend on the server jar and the arguments
        }
        deleteTree(dataDirectory);
        Files.createDirectories(dataDirectory);

        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-DbundlerMainClass=net.minecraft.data.Main");
        command.add("-jar");
        command.add(serverJar.toAbsolutePath().toString());
        command.addAll(settings.dataGeneratorArguments());
        log("Running Minecraft's data generator: " + command);

        final Process process = new ProcessBuilder(command).directory(dataDirectory.toFile()).redirectErrorStream(true).start();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                log("[Minecraft Data Generator] " + line);
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Minecraft data generator exited with code " + exitCode);
        }
        if (!Files.isRegularFile(reports.resolve("registries.json"))) {
            throw new IOException("Minecraft data generator exited successfully, but did not generate "
                    + reports.resolve("registries.json"));
        }
        Files.writeString(stamp, arguments);
    }

    /** Downloads Prismarine minecraft-data's full {@code pc/<version>} data directory. */
    private Path downloadPrismarineData() throws IOException, InterruptedException {
        final String version = settings.prismarineMinecraftData();
        final Path out = settings.workingDirectory().resolve("prismarine").resolve(version);
        final Path stamp = out.resolve(".source");
        final String source = settings.prismarineDataRepository() + "@" + settings.prismarineDataRef();
        if (Files.exists(stamp) && Files.readString(stamp).equals(source)) {
            return out;
        }
        log("Downloading minecraft-data pc/" + version + " from " + source);
        deleteTree(out);
        Files.createDirectories(out);

        final String contentsUrl = CONTENTS_URL_TEMPLATE.formatted(settings.prismarineDataRepository(), version,
                settings.prismarineDataRef());
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(contentsUrl))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Fidorial-registry-generator")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned HTTP " + response.statusCode() + " listing " + contentsUrl);
        }
        final JsonArray entries = JsonParser.parseString(response.body()).getAsJsonArray();
        for (final JsonElement element : entries) {
            final JsonObject entry = element.getAsJsonObject();
            if (!"file".equals(entry.get("type").getAsString())) {
                continue; // no subdirectories expected
            }
            final String downloadUrl = entry.get("download_url").getAsString();
            try (InputStream input = URI.create(downloadUrl).toURL().openStream()) {
                Files.copy(input, out.resolve(entry.get("name").getAsString()), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                throw new IOException("Failed to download " + downloadUrl, e);
            }
        }
        Files.writeString(stamp, source);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Newer versions write one file per item, older ones a single items.json. */
    private Path itemComponentsReport() throws IOException {
        for (final Path candidate : List.of(itemComponents, reports.resolve("items.json"))) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("No item components report: run the data generator with \"--reports\".");
    }

    private void requireVanilla(final String directory) {
        if (!Files.isDirectory(vanillaData.resolve("minecraft").resolve(directory))) {
            throw new IllegalStateException("No vanilla " + directory + " in " + vanillaData
                    + ": add \"--server\" to dataGeneratorArguments so that Mojang dumps them.");
        }
    }

    private static Path existing(final Path directory) {
        return Files.isDirectory(directory) ? directory : null;
    }

    /**
     * Replaces the module's generated source folder ({@code src/generated/java}) with the staged sources, so
     * what an earlier run generated and this one did not is gone.
     */
    private void publishSources() throws IOException {
        final Path module = settings.module();
        final Path target = settings.generatedSourcesDirectory();
        deleteTree(target);
        int written = 0;
        try (Stream<Path> walk = Files.walk(staging)) {
            for (final Path file : walk.filter(Files::isRegularFile).toList()) {
                final Path destination = target.resolve(staging.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                written++;
            }
        }
        log("Wrote " + written + " generated source(s) into " + module.relativize(target));
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    private void log(final String message) {
        System.out.println("[" + settings.module().getFileName() + "] " + message);
    }
}
