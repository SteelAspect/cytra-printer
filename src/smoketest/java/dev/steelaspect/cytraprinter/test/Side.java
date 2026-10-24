package dev.steelaspect.cytraprinter.test;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.function.Function;

/**
 * Where the checks act and look: the singleplayer server itself, or a dedicated server reached only through
 * commands (then the world is read from the client's copy).
 */
interface Side {
    void cmd(String command);

    /** Moves the player there, flying (creative or allowed flight). */
    void teleport(double x, double y, double z, float yaw, float pitch);

    /** Reads the world on its own thread. */
    <R> R read(Function<Level, R> f);

    static Side singleplayer(IntegratedServer server) {
        return new Side() {
            @Override
            public void cmd(String command) {
                server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)).join();
            }

            @Override
            public void teleport(double x, double y, double z, float yaw, float pitch) {
                server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.getAbilities().mayfly = true;
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    p.teleportTo(server.overworld(), x, y, z, yaw, pitch);
                }).join();
            }

            @Override
            public <R> R read(Function<Level, R> f) {
                return server.submit(() -> f.apply(server.overworld())).join();
            }
        };
    }

    /** A dedicated server: the player is op there, so commands do the setup. */
    static Side dedicated(Ctx t) {
        return new Side() {
            @Override
            public void cmd(String command) {
                t.client(mc -> mc.player.connection.sendCommand(command));
            }

            @Override
            public void teleport(double x, double y, double z, float yaw, float pitch) {
                t.client(mc -> {
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                    mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
                });
            }

            @Override
            public <R> R read(Function<Level, R> f) {
                return t.get(mc -> f.apply(mc.level));
            }
        };
    }
}
