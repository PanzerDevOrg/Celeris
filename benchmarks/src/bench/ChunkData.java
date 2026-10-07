package bench;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.SplittableRandom;

/**
 * Synthetic data shaped like what Minecraft compresses: NBT-style chunk
 * sections (block-state palettes as strings, packed long arrays of palette
 * indices with spatial coherence, heightmaps, light arrays) and region-sized
 * runs of them. Deterministic per seed.
 */
public final class ChunkData {
    private static final String[] BLOCKS = {
            "minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block",
            "minecraft:air", "minecraft:water", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite", "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gravel",
            "minecraft:oak_log", "minecraft:oak_leaves[distance=1,persistent=false]", "minecraft:tuff",
            "minecraft:copper_ore", "minecraft:lava[level=0]", "minecraft:sand"};

    private ChunkData() {
    }

    /** About {@code target} bytes of chunk sections. */
    public static byte[] generate(int target, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(target + 8192);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            int section = 0;
            while (bytes.size() < target) {
                writeSection(out, r, section++);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] all = bytes.toByteArray();
        return java.util.Arrays.copyOf(all, target);
    }

    private static void writeSection(DataOutputStream out, SplittableRandom r, int index) throws IOException {
        out.writeByte(10);
        out.writeUTF("section");
        out.writeByte(1);
        out.writeUTF("Y");
        out.writeByte(index % 24 - 4);
        int paletteSize = 2 + r.nextInt(11);
        out.writeByte(9);
        out.writeUTF("palette");
        out.writeByte(10);
        out.writeInt(paletteSize);
        int base = r.nextInt(BLOCKS.length);
        for (int i = 0; i < paletteSize; i++) {
            out.writeByte(8);
            out.writeUTF("Name");
            out.writeUTF(BLOCKS[(base + i * (1 + r.nextInt(3))) % BLOCKS.length]);
            out.writeByte(0);
        }
        // 4096 palette indices, bits per entry from the palette size, spatially coherent runs.
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int perLong = 64 / bits;
        int longs = (4096 + perLong - 1) / perLong;
        out.writeByte(12);
        out.writeUTF("data");
        out.writeInt(longs);
        int current = 0;
        int runLeft = 0;
        for (int l = 0; l < longs; l++) {
            long word = 0;
            for (int k = 0; k < perLong; k++) {
                if (runLeft-- <= 0) {
                    current = r.nextDouble() < 0.7 ? 0 : r.nextInt(paletteSize);
                    runLeft = 4 + r.nextInt(60);
                }
                word |= ((long) current) << (k * bits);
            }
            out.writeLong(word);
        }
        // Block light and sky light: mostly 0 or 15, with gradients.
        out.writeByte(7);
        out.writeUTF("SkyLight");
        out.writeInt(2048);
        for (int i = 0; i < 2048; i++) {
            int y = i / 128;
            int v = y > 8 ? 0xFF : (r.nextDouble() < 0.9 ? 0x00 : (r.nextInt(16) << 4 | r.nextInt(16)));
            out.writeByte(v);
        }
        // Heightmap: 37 longs of 9-bit heights that change slowly.
        out.writeByte(12);
        out.writeUTF("MOTION_BLOCKING");
        out.writeInt(37);
        int h = 64 + r.nextInt(40);
        for (int l = 0; l < 37; l++) {
            long word = 0;
            for (int k = 0; k < 7; k++) {
                h = Math.max(-64, Math.min(319, h + r.nextInt(3) - 1));
                word |= ((long) (h + 64)) << (k * 9);
            }
            out.writeLong(word);
        }
        out.writeByte(0);
    }
}
