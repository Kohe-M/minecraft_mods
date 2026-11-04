package com.omuraisu.minimap.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

// 地図に出す他のプレイヤー。
// 見えている相手は正確な位置に、遠くの相手はバニラのロケーターバー用に届く情報から出す
final class PlayerMarkers {
	record Marker(PlayerSkin skin, String name, double x, double z) {
	}

	// 方角しか届かない相手は十分遠くに置いて、地図の縁に出す
	private static final double UNKNOWN_DISTANCE = 1_000_000;

	static List<Marker> collect(Minecraft client, float partialTick) {
		LocalPlayer self = client.player;
		ClientLevel level = client.level;
		ClientPacketListener connection = client.getConnection();
		List<Marker> markers = new ArrayList<>();
		if (self == null || level == null || connection == null) {
			return markers;
		}

		Set<UUID> seen = new HashSet<>();
		seen.add(self.getUUID());
		for (AbstractClientPlayer other : level.players()) {
			if (seen.add(other.getUUID()) && isVisibleTo(other, self)) {
				Vec3 position = other.getPosition(partialTick);
				markers.add(new Marker(other.getSkin(), other.getGameProfile().name(), position.x, position.z));
			}
		}

		Vec3 origin = self.getPosition(partialTick);
		// yaw 0（南向き）のカメラから見た角度を、そのままワールドの方角として使う
		TrackedWaypoint.Camera camera = new TrackedWaypoint.Camera() {
			@Override
			public float yaw() {
				return 0;
			}

			@Override
			public Vec3 position() {
				return origin;
			}
		};
		connection.getWaypointManager().forEachWaypoint(self, waypoint -> {
			Optional<UUID> id = waypoint.id().left();
			if (id.isEmpty() || seen.contains(id.get())) {
				return;
			}
			PlayerInfo info = connection.getPlayerInfo(id.get());
			double yaw = waypoint.yawAngleToCamera(level, camera, entity -> partialTick);
			if (info == null || Double.isNaN(yaw)) {
				return;
			}
			double distanceSquared = waypoint.distanceSquared(self);
			double distance = Double.isInfinite(distanceSquared) ? UNKNOWN_DISTANCE : Math.sqrt(distanceSquared);
			double radians = Math.toRadians(yaw);
			markers.add(new Marker(info.getSkin(), info.getProfile().name(),
					origin.x - Math.sin(radians) * distance, origin.z + Math.cos(radians) * distance));
		});
		return markers;
	}

	private static boolean isVisibleTo(AbstractClientPlayer other, LocalPlayer self) {
		return !other.isInvisibleTo(self) && (!other.isSpectator() || self.isSpectator());
	}

	private PlayerMarkers() {
	}
}
