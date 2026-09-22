package com.j0ker2j0ker.swd.client.util;

import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns a Flashback recording (.zip) straight into a void world.
 * It reads the chunk cache (level_chunk_caches) inside the recording,
 * so it does not need to play the replay.
 */
public class FlashbackConverter {

    private static final int DATA_VERSION = 4671; // 1.21.11

    // Biome order the server sends (vanilla, alphabetical)
    private static final String[] BIOMES = {
            "badlands", "bamboo_jungle", "basalt_deltas", "beach", "birch_forest", "cherry_grove", "cold_ocean",
            "crimson_forest", "dark_forest", "deep_cold_ocean", "deep_dark", "deep_frozen_ocean", "deep_lukewarm_ocean",
            "deep_ocean", "desert", "dripstone_caves", "end_barrens", "end_highlands", "end_midlands", "eroded_badlands",
            "flower_forest", "forest", "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes",
            "jagged_peaks", "jungle", "lukewarm_ocean", "lush_caves", "mangrove_swamp", "meadow", "mushroom_fields",
            "nether_wastes", "ocean", "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "pale_garden", "plains", "river", "savanna", "savanna_plateau", "small_end_islands", "snowy_beach",
            "snowy_plains", "snowy_slopes", "snowy_taiga", "soul_sand_valley", "sparse_jungle", "stony_peaks",
            "stony_shore", "sunflower_plains", "swamp", "taiga", "the_end", "the_void", "warm_ocean", "warped_forest",
            "windswept_forest", "windswept_gravelly_hills", "windswept_hills", "windswept_savanna", "wooded_badlands"
    };

    /** Converts the recording and returns the new world folder. */
    public static Path convert(Path zip, Path savesDir, Consumer<String> status) throws IOException {
        status.accept("Reading recording...");
        Map<Long, List<byte[]>> versions = readChunkPackets(zip);
        if (versions.isEmpty()) throw new IOException("No chunks found in this recording");

        String name = zip.getFileName().toString().replaceAll("(?i)\\.zip$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        Path world = savesDir.resolve(name);
        int n = 2;
        while (Files.exists(world)) world = savesDir.resolve(name + " (" + n++ + ")");
        Path regionDir = world.resolve("region");
        Files.createDirectories(regionDir);

        Map<Long, List<CompoundTag>> regions = new HashMap<>();
        int done = 0, failed = 0, skipped = 0;
        long sumX = 0, sumZ = 0;
        for (List<byte[]> list : versions.values()) {
            try {
                // Newest real (not floating stone) version of this chunk
                CompoundTag chunk = null;
                for (int v = list.size() - 1; v >= 0 && chunk == null; v--) chunk = parseChunk(list.get(v));
                if (chunk == null) { skipped++; continue; }
                int x = chunk.getIntOr("xPos", 0), z = chunk.getIntOr("zPos", 0);
                sumX += x; sumZ += z;
                regions.computeIfAbsent(((long) (x >> 5) << 32) | ((z >> 5) & 0xFFFFFFFFL), k -> new ArrayList<>()).add(chunk);
                done++;
            } catch (Exception e) {
                failed++;
            }
            if (done % 100 == 0) status.accept("Converting chunks: " + done + " / " + versions.size());
        }

        status.accept("Writing world...");
        for (Map.Entry<Long, List<CompoundTag>> e : regions.entrySet()) {
            int rx = (int) (e.getKey() >> 32), rz = (int) (long) e.getKey();
            writeRegion(regionDir.resolve("r." + rx + "." + rz + ".mca"), e.getValue());
        }

        int spawnX = (int) (sumX / Math.max(1, done)) * 16 + 8;
        int spawnZ = (int) (sumZ / Math.max(1, done)) * 16 + 8;
        writeLevelDat(world, world.getFileName().toString(), spawnX, 100, spawnZ);

        status.accept("Done! Saved " + done + " chunks to world \"" + world.getFileName() + "\""
                + " (removed " + skipped + " floating stone chunks)"
                + (failed > 0 ? " (" + failed + " chunks failed)" : ""));
        return world;
    }

    private static Map<Long, List<byte[]>> readChunkPackets(Path zip) throws IOException {
        Map<Long, List<byte[]>> versions = new LinkedHashMap<>(); // every copy of every chunk, oldest first
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<ZipEntry> caches = new ArrayList<>();
            zf.stream().filter(e -> e.getName().startsWith("level_chunk_caches/") && !e.isDirectory()).forEach(caches::add);
            caches.sort(Comparator.comparingInt(FlashbackConverter::cacheIndex));
            for (ZipEntry entry : caches) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(zf.getInputStream(entry)))) {
                    while (true) {
                        int len;
                        try { len = in.readInt(); } catch (EOFException end) { break; }
                        byte[] data = new byte[len];
                        in.readFully(data);
                        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                        buf.readVarInt();
                        int x = buf.readInt(), z = buf.readInt();
                        versions.computeIfAbsent(((long) x << 32) | (z & 0xFFFFFFFFL), k -> new ArrayList<>()).add(data);
                    }
                }
            }
        }
        return versions;
    }

    private static int cacheIndex(ZipEntry e) {
        try { return Integer.parseInt(e.getName().substring(e.getName().lastIndexOf('/') + 1)); }
        catch (NumberFormatException ex) { return Integer.MAX_VALUE; }
    }

    private static CompoundTag parseChunk(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        buf.readVarInt(); // packet id
        int cx = buf.readInt(), cz = buf.readInt();

        int heightmaps = buf.readVarInt();
        for (int i = 0; i < heightmaps; i++) {
            buf.readVarInt();
            int longs = buf.readVarInt();
            buf.skipBytes(longs * 8);
        }

        int size = buf.readVarInt();
        FriendlyByteBuf sectionsBuf = new FriendlyByteBuf(buf.readBytes(size));
        List<int[][]> sections = new ArrayList<>();
        while (sectionsBuf.isReadable()) {
            sectionsBuf.readShort(); // non-air block count
            int[] blocks = readContainer(sectionsBuf, 4096, 8);
            int[] biomes = readContainer(sectionsBuf, 64, 3);
            sections.add(new int[][]{blocks, biomes});
        }
        if (isFloatingStone(sections)) return null;
        int minSection = sections.size() == 24 ? -4 : 0;

        ListTag blockEntities = new ListTag();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            int xz = buf.readUnsignedByte();
            int y = buf.readShort();
            int type = buf.readVarInt();
            CompoundTag tag = buf.readNbt();
            if (tag == null) tag = new CompoundTag();
            BlockEntityType<?> beType = BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(type);
            if (beType == null) continue;
            tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(beType).toString());
            tag.putInt("x", cx * 16 + (xz >> 4));
            tag.putInt("y", y);
            tag.putInt("z", cz * 16 + (xz & 15));
            blockEntities.add(tag);
        }

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.putInt("xPos", cx);
        root.putInt("zPos", cz);
        root.putInt("yPos", minSection);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0);
        root.putLong("InhabitedTime", 0);
        root.putBoolean("isLightOn", false);

        ListTag sectionList = new ListTag();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag s = new CompoundTag();
            s.putByte("Y", (byte) (minSection + i));
            s.put("block_states", blockStates(sections.get(i)[0]));
            s.put("biomes", biomes(sections.get(i)[1]));
            sectionList.add(s);
        }
        root.put("sections", sectionList);
        root.put("block_entities", blockEntities);
        root.put("block_ticks", new ListTag());
        root.put("fluid_ticks", new ListTag());
        root.put("Heightmaps", new CompoundTag());
        CompoundTag structures = new CompoundTag();
        structures.put("References", new CompoundTag());
        structures.put("starts", new CompoundTag());
        root.put("structures", structures);
        return root;
    }

    /**
     * The server fills far away chunks with fake floating stone.
     * Fake chunks are only stone, and most of the stone blocks don't touch any other block.
     */
    private static boolean isFloatingStone(List<int[][]> sections) {
        int stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        int height = sections.size() * 16;
        boolean[] solid = new boolean[16 * 16 * height];
        int count = 0;
        for (int s = 0; s < sections.size(); s++) {
            int[] blocks = sections.get(s)[0];
            for (int i = 0; i < 4096; i++) {
                int id = blocks[i];
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (state == null || state.isAir()) continue;
                if (id != stone) return false; // has other blocks -> real chunk
                int x = i & 15, z = (i >> 4) & 15, y = s * 16 + (i >> 8);
                solid[(y * 16 + z) * 16 + x] = true;
                count++;
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

    private static int[] readContainer(FriendlyByteBuf buf, int size, int maxIndirect) {
        int bits = buf.readUnsignedByte();
        int[] result = new int[size];
        if (bits == 0) {
            Arrays.fill(result, buf.readVarInt());
            return result;
        }
        int[] palette = null;
        if (bits <= maxIndirect) {
            palette = new int[buf.readVarInt()];
            for (int i = 0; i < palette.length; i++) palette[i] = buf.readVarInt();
        }
        int perLong = 64 / bits;
        int longs = (size + perLong - 1) / perLong;
        long mask = (1L << bits) - 1;
        int idx = 0;
        for (int i = 0; i < longs; i++) {
            long v = buf.readLong();
            for (int j = 0; j < perLong && idx < size; j++) {
                int val = (int) ((v >> (j * bits)) & mask);
                result[idx++] = palette != null ? palette[val] : val;
            }
        }
        return result;
    }

    private static CompoundTag blockStates(int[] ids) {
        List<Integer> palette = new ArrayList<>();
        Map<Integer, Integer> map = new HashMap<>();
        int[] idx = new int[ids.length];
        for (int i = 0; i < ids.length; i++) idx[i] = map.computeIfAbsent(ids[i], k -> { palette.add(k); return palette.size() - 1; });

        ListTag list = new ListTag();
        for (int id : palette) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            list.add(NbtUtils.writeBlockState(state));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", list);
        if (palette.size() > 1) tag.putLongArray("data", pack(idx, Math.max(4, bits(palette.size()))));
        return tag;
    }

    private static CompoundTag biomes(int[] ids) {
        List<Integer> palette = new ArrayList<>();
        Map<Integer, Integer> map = new HashMap<>();
        int[] idx = new int[ids.length];
        for (int i = 0; i < ids.length; i++) idx[i] = map.computeIfAbsent(ids[i], k -> { palette.add(k); return palette.size() - 1; });

        ListTag list = new ListTag();
        for (int id : palette) list.add(StringTag.valueOf("minecraft:" + (id >= 0 && id < BIOMES.length ? BIOMES[id] : "plains")));
        CompoundTag tag = new CompoundTag();
        tag.put("palette", list);
        if (palette.size() > 1) tag.putLongArray("data", pack(idx, bits(palette.size())));
        return tag;
    }

    private static int bits(int n) {
        int b = 0;
        while ((1 << b) < n) b++;
        return b;
    }

    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        return out;
    }

    private static void writeRegion(Path file, List<CompoundTag> chunks) throws IOException {
        byte[] header = new byte[8192];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int sector = 2;
        int now = (int) (System.currentTimeMillis() / 1000);
        for (CompoundTag chunk : chunks) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(compressed))) {
                NbtIo.write(chunk, out);
            }
            byte[] z = compressed.toByteArray();
            int len = z.length + 1;
            int sectors = (len + 4 + 4095) / 4096;
            int i = ((chunk.getIntOr("xPos", 0) & 31) + (chunk.getIntOr("zPos", 0) & 31) * 32) * 4;
            header[i] = (byte) (sector >> 16); header[i + 1] = (byte) (sector >> 8); header[i + 2] = (byte) sector; header[i + 3] = (byte) sectors;
            header[4096 + i] = (byte) (now >> 24); header[4096 + i + 1] = (byte) (now >> 16); header[4096 + i + 2] = (byte) (now >> 8); header[4096 + i + 3] = (byte) now;
            byte[] buf = new byte[sectors * 4096];
            buf[0] = (byte) (len >> 24); buf[1] = (byte) (len >> 16); buf[2] = (byte) (len >> 8); buf[3] = (byte) len;
            buf[4] = 2; // zlib
            System.arraycopy(z, 0, buf, 5, z.length);
            body.write(buf);
            sector += sectors;
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header);
            body.writeTo(out);
        }
    }

    private static void writeLevelDat(Path world, String name, int x, int y, int z) throws IOException {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", DATA_VERSION);
        data.putInt("version", 19133);
        data.putString("LevelName", name);
        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("initialized", true);
        data.putBoolean("hardcore", false);
        data.putByte("Difficulty", (byte) 0);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putLong("Time", 0);
        data.putLong("DayTime", 6000);

        CompoundTag spawn = new CompoundTag();
        spawn.putIntArray("pos", new int[]{x, y, z});
        spawn.putFloat("yaw", 0);
        spawn.putFloat("pitch", 0);
        spawn.putString("dimension", "minecraft:overworld");
        data.put("spawn", spawn);

        CompoundTag rules = new CompoundTag();
        rules.putBoolean("minecraft:advance_time", false);
        rules.putBoolean("minecraft:advance_weather", false);
        rules.putBoolean("minecraft:spawn_mobs", false);
        rules.putBoolean("minecraft:spawn_monsters", false);
        data.put("game_rules", rules);

        CompoundTag version = new CompoundTag();
        version.putInt("Id", DATA_VERSION);
        version.putString("Name", "1.21.11");
        version.putString("Series", "main");
        version.putBoolean("Snapshot", false);
        data.put("Version", version);

        CompoundTag packs = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        packs.put("Enabled", enabled);
        packs.put("Disabled", new ListTag());
        data.put("DataPacks", packs);

        CompoundTag dims = new CompoundTag();
        dims.put("minecraft:overworld", voidDimension("minecraft:overworld", "minecraft:the_void"));
        dims.put("minecraft:the_nether", voidDimension("minecraft:the_nether", "minecraft:nether_wastes"));
        dims.put("minecraft:the_end", voidDimension("minecraft:the_end", "minecraft:the_end"));
        CompoundTag gen = new CompoundTag();
        gen.putLong("seed", 0);
        gen.putBoolean("generate_features", false);
        gen.putBoolean("bonus_chest", false);
        gen.put("dimensions", dims);
        data.put("WorldGenSettings", gen);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, world.resolve("level.dat"));
    }

    private static CompoundTag voidDimension(String type, String biome) {
        CompoundTag settings = new CompoundTag();
        settings.put("layers", new ListTag());
        settings.putString("biome", biome);
        settings.putBoolean("features", false);
        settings.putBoolean("lakes", false);
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:flat");
        generator.put("settings", settings);
        CompoundTag dim = new CompoundTag();
        dim.putString("type", type);
        dim.put("generator", generator);
        return dim;
    }
}
