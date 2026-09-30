package com.j0ker2j0ker.swd.client.mixin;

import com.j0ker2j0ker.swd.client.util.ChunkFilter;
import com.j0ker2j0ker.swd.client.util.SaveManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if(!SaveManager.isSaving) return;

        Minecraft mc = Minecraft.getInstance();
        // Removed the "isLocalServer / no server" check so chunks also get saved
        // while watching a Flashback or Replay Mod recording.
        int chunkX = packet.getX();
        int chunkZ = packet.getZ();
        LevelChunk wc = this.level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (wc == null || wc.isEmpty() || mc.level == null) return;

        // Don't save fake floating stone chunks (and don't let them overwrite real ones)
        if (ChunkFilter.isFloatingStone(wc)) return;

        SaveManager.saveChunkToRegion(SaveManager.path, wc, false, mc.level.dimension());
        ChunkFilter.markSaved(wc);
    }

    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void handleAwardStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        SaveManager.cacheAwardStatsPacket(packet);
    }

    @Inject(method = "handleUpdateAdvancementsPacket", at = @At("TAIL"))
    private void handleUpdateAdvancementsPacket(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        SaveManager.cacheAdvancementPacket(packet);
    }
}
