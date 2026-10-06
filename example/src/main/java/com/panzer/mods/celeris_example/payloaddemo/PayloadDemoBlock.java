package com.panzer.mods.celeris_example.payloaddemo;

import com.panzer.mods.celeris.core.memory.PacketPipeline;
import com.panzer.mods.celeris.network.CompressedPayload;
import com.panzer.mods.celeris.network.PayloadCompression;
import com.panzer.mods.celeris.network.NetworkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A real, working demo of {@link PacketPipeline}
 * via the public {@link PayloadCompression} facade: right-click builds a
 * repetitive ~24 KB payload (the kind of data zstd is good at -- bulk
 * world/inventory snapshots, not single small packets), compresses it,
 * decompresses it back, and reports the real numbers in chat.
 *
 * <p>This is a local round-trip, not an actual network send -- {@link
 * PayloadCompression#compress} returns exactly the {@link CompressedPayload}
 * that would go on the wire via {@link NetworkRegistry},
 * so the byte counts shown here are the real ones, just without an actual
 * client/server hop.
 */
public final class PayloadDemoBlock extends Block {

    private static final int DEMO_PAYLOAD_REPEATS = 512;
    private static final byte[] DEMO_PATTERN =
            "Celeris off-heap zstd compression demo payload. ".getBytes(StandardCharsets.UTF_8);

    public PayloadDemoBlock(Properties props) {
        super(props);
    }

    @Override
    public @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player, @NotNull BlockHitResult hit) {
        if (!level.isClientSide) {
            runDemo(player);
        }
        return InteractionResult.SUCCESS;
    }

    private static void runDemo(Player player) {
        byte[] original = buildDemoPayload();
        CompressedPayload result = PayloadCompression.compress(original);
        byte[] roundTrip = PayloadCompression.decompress(result);
        boolean roundTripOk = Arrays.equals(original, roundTrip);

        double compressedPercent = 100.0 * result.data().length / original.length;

        player.displayClientMessage(Component.literal(String.format(
                "Celeris PayloadCompression: %d bytes -> %d bytes (%.1f%%), compressed=%s, round-trip OK=%s",
                original.length, result.data().length, compressedPercent, result.compressed(), roundTripOk
        )), false);
    }

    /** ~24 KB of a repeating pattern -- easy to compress well, and well over the 256-byte threshold {@link PayloadCompression} applies before bothering to compress at all. */
    private static byte[] buildDemoPayload() {
        byte[] payload = new byte[DEMO_PATTERN.length * DEMO_PAYLOAD_REPEATS];
        for (int i = 0; i < DEMO_PAYLOAD_REPEATS; i++) {
            System.arraycopy(DEMO_PATTERN, 0, payload, i * DEMO_PATTERN.length, DEMO_PATTERN.length);
        }
        return payload;
    }
}
