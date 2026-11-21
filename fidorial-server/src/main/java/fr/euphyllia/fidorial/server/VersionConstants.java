package fr.euphyllia.fidorial.server;

import fr.fidorial.version.BuildSpecification;
import fr.fidorial.version.MinecraftVersion;

import java.util.OptionalInt;

/**
 * Holds the constants for the current Minecraft version targeted by Fidorial.
 *
 * <p>The values come from the {@code define} block of {@code fidorial-server/project.jsonc}.
 */
public final class VersionConstants {

    public static final String MINECRAFT_VERSION_ID = BuildConfig.MINECRAFT_VERSION_ID;
    public static final String MINECRAFT_VERSION_NAME = BuildConfig.MINECRAFT_VERSION_NAME;
    /** MAJOR.MINOR or MAJOR.MINOR.PATCH, as opposed to a snapshot, pre-release or release candidate. */
    public static final boolean IS_RELEASE = MINECRAFT_VERSION_ID.matches("\\d+\\.\\d+(?:\\.\\d+)?");

    public static final String FIDORIAL_VERSION = BuildConfig.VERSION;
    public static final int BUILD_NUMBER = BuildConfig.BUILD_NUMBER; // -1 for a local build
    // CI -> <minecraft version>-build.<build>+<fidorial version>
    // local -> <minecraft version>-local+<fidorial version>
    public static final String SERVER_VERSION = MINECRAFT_VERSION_ID
            + (BUILD_NUMBER < 0 ? "-local" : "-build." + BUILD_NUMBER) + "+" + FIDORIAL_VERSION;

    public static final int PROTOCOL_VERSION = BuildConfig.PROTOCOL_VERSION;
    public static final int DATA_VERSION = BuildConfig.DATA_VERSION;

    public static final BuildSpecification BUILD = new BuildSpecification(
            MinecraftVersion.parse(MINECRAFT_VERSION_ID),
            PROTOCOL_VERSION,
            FIDORIAL_VERSION,
            SERVER_VERSION,
            BUILD_NUMBER < 0 ? OptionalInt.empty() : OptionalInt.of(BUILD_NUMBER)
    );

    private VersionConstants() {
    }
}
