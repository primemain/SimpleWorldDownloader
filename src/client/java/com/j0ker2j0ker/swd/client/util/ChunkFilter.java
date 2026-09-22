package com.j0ker2j0ker.swd.client.util;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Skips fake floating stone chunks and counts saved chunks while playing. */
public class ChunkFilter {

    private static final Set<Long> saved = new HashSet<>();
    private static Path savedFor = null;

    /** Remembers a saved chunk. Starts a new count when a new download starts. */
    public static void markSaved(LevelChunk chunk) {
        if (savedFor != SaveManager.path) {
            saved.clear();
            savedFor = SaveManager.path;
        }
        saved.add(chunk.getPos().toLong());
    }

    public static int savedCount() {
        return savedFor == SaveManager.path ? saved.size() : 0;
    }

    /**
     * The server fills far away chunks with fake floating stone.
     * Fake chunks are only stone, and most of the stone blocks don't touch any other block.
     */
    public static boolean isFloatingStone(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int height = sections.length * 16;
        boolean[] solid = new boolean[16 * 16 * height];
        int count = 0;
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        if (!state.is(Blocks.STONE)) return false; // has other blocks -> real chunk
                        solid[((s * 16 + y) * 16 + z) * 16 + x] = true;
                        count++;
                    }
                }
            }
        }
        if (count == 0) return false;

        int alone = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!solid[(y * 16 + z) * 16 + x]) continue;
                    boolean touching =
                            (x > 0 && solid[(y * 16 + z) * 16 + x - 1]) ||
                            (x < 15 && solid[(y * 16 + z) * 16 + x + 1]) ||
                            (z > 0 && solid[(y * 16 + z - 1) * 16 + x]) ||
                            (z < 15 && solid[(y * 16 + z + 1) * 16 + x]) ||
                            (y > 0 && solid[((y - 1) * 16 + z) * 16 + x]) ||
                            (y < height - 1 && solid[((y + 1) * 16 + z) * 16 + x]);
                    if (!touching) alone++;
                }
            }
        }
        return alone * 2 > count; // more than half the stone is floating alone
    }
}
