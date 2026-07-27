package com.chestmemory.client.mixin;

import com.chestmemory.client.data.WorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the per-world hashed seed from login and respawn packets.
 * <p>
 * This is the only per-world identity the vanilla protocol offers: a multiworld server's
 * farm and build worlds both arrive as {@code minecraft:overworld}, but each carries its own
 * seed hash — the same value for every player in that world, stable across sessions. The
 * custom portal between such worlds is a respawn on the wire, so both handlers are hooked.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("HEAD"))
	private void chestmemory$loginSeed(ClientboundLoginPacket packet, CallbackInfo ci) {
		WorldIdentity.onSpawnPacket(packet.commonPlayerSpawnInfo().seed());
	}

	@Inject(method = "handleLogin", at = @At("RETURN"))
	private void chestmemory$loginBind(ClientboundLoginPacket packet, CallbackInfo ci) {
		WorldIdentity.bind(Minecraft.getInstance().level);
	}

	@Inject(method = "handleRespawn", at = @At("HEAD"))
	private void chestmemory$respawnSeed(ClientboundRespawnPacket packet, CallbackInfo ci) {
		WorldIdentity.onSpawnPacket(packet.commonPlayerSpawnInfo().seed());
	}

	@Inject(method = "handleRespawn", at = @At("RETURN"))
	private void chestmemory$respawnBind(ClientboundRespawnPacket packet, CallbackInfo ci) {
		WorldIdentity.bind(Minecraft.getInstance().level);
	}
}
