package eu.codedsakura.mods.callback;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;

public interface DieRegistrationCallback {
	Event<DieRegistrationCallback> EVENT = EventFactory.createArrayBacked(DieRegistrationCallback.class, (callbacks) -> (player) -> {
		for (DieRegistrationCallback callback : callbacks) {
			callback.register(player);
		}
	});

	void register(ServerPlayerEntity player);
}