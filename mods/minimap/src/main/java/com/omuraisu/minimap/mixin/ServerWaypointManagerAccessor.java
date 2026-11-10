package com.omuraisu.minimap.mixin;

import com.google.common.collect.Table;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;

@Mixin(ServerWaypointManager.class)
public interface ServerWaypointManagerAccessor {
	@Accessor("connections")
	Table<ServerPlayer, WaypointTransmitter, WaypointTransmitter.Connection> minimap$connections();
}
