package com.korosoft.keyinput;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side registry of live pings, fed by {@link PingBroadcastPayload} from the server and
 * drawn every frame by {@link PingRenderer}. Mirrors Ping-Wheel's PingManager/PingView.
 */
public final class PingManager {

    private static final List<PingState> PINGS = new ArrayList<>();

    private PingManager() {
    }

    public static void clear() {
        synchronized (PINGS) {
            PINGS.clear();
        }
    }

    /** Adds or replaces a ping by (author, sequence) — the server re-sends for corrections. */
    public static void addOrReplace(PingState ping) {
        synchronized (PINGS) {
            for (int i = 0; i < PINGS.size(); i++) {
                PingState existing = PINGS.get(i);
                if (Objects.equals(existing.getAuthor(), ping.getAuthor())
                        && existing.getSequence() == ping.getSequence()) {
                    PINGS.set(i, ping);
                    return;
                }
            }
            PINGS.add(ping);
        }
    }

    /** Called every frame; follows entities and drops expired pings. */
    public static void update() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int dimension = mc.world != null ? mc.world.getRegistryKey().getValue().hashCode() : 0;
        synchronized (PINGS) {
            Iterator<PingState> it = PINGS.iterator();
            while (it.hasNext()) {
                PingState ping = it.next();
                if (ping.getDimension() != dimension) {
                    it.remove();
                    continue;
                }
                ping.followEntity();
                if (ping.isExpired()) {
                    it.remove();
                }
            }
        }
    }

    public static List<PingState> snapshot() {
        synchronized (PINGS) {
            return new ArrayList<>(PINGS);
        }
    }
}
