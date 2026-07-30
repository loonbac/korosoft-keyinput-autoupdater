package com.korosoft.keyinput;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This build's version, encoded as a single int for {@link HelloPayload}: {@code major * 10000 +
 * minor * 100 + patch}, so 1.7.0 is 10700 and plain integer comparison orders builds correctly.
 *
 * <p>Read from the mod's own metadata rather than a constant here. Jars are hand-distributed and
 * the version in build.gradle is the one thing that must always be bumped; a second copy of it in
 * source is a copy someone eventually forgets, and the failure mode — a stale jar announcing
 * itself as current and getting waved through the gate — is silent.
 */
public final class ModVersion {

    private static final Logger LOGGER = LoggerFactory.getLogger("keyinput");

    private static final String MOD_ID = "keyinput";

    /** Sent when the version cannot be determined. Sorts below every real build, so it fails closed. */
    public static final int UNKNOWN = 0;

    private static final int COMPONENT_LIMIT = 99;

    private static final int ENCODED = encodeFromLoader();

    private ModVersion() {
    }

    /** This build's encoded version. Resolved once at class load; never changes at runtime. */
    public static int encoded() {
        return ENCODED;
    }

    private static int encodeFromLoader() {
        Version version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion())
                .orElse(null);

        if (version == null) {
            LOGGER.warn("[keyinput] could not find our own mod container; announcing version {} to the server", UNKNOWN);
            return UNKNOWN;
        }

        if (!(version instanceof SemanticVersion semantic)) {
            LOGGER.warn("[keyinput] mod version '{}' is not semantic and cannot be encoded; announcing {}",
                    version.getFriendlyString(), UNKNOWN);
            return UNKNOWN;
        }

        int major = component(semantic, 0);
        int minor = component(semantic, 1);
        int patch = component(semantic, 2);

        // the encoding only has two decimal digits per slot; louder than silently wrapping into
        // the next slot and announcing a version this build is not
        if (minor > COMPONENT_LIMIT || patch > COMPONENT_LIMIT) {
            LOGGER.warn("[keyinput] version '{}' does not fit the major*10000+minor*100+patch encoding; "
                    + "the server will be told a wrong version", version.getFriendlyString());
        }

        return major * 10000
                + Math.clamp(minor, 0, COMPONENT_LIMIT) * 100
                + Math.clamp(patch, 0, COMPONENT_LIMIT);
    }

    private static int component(SemanticVersion version, int index) {
        if (index >= version.getVersionComponentCount()) {
            return 0;
        }
        // COMPONENT_WILDCARD is Integer.MIN_VALUE and only ever appears in version predicates, not
        // in a concrete build's own version — but a negative here would wreck the arithmetic
        return Math.max(version.getVersionComponent(index), 0);
    }
}
