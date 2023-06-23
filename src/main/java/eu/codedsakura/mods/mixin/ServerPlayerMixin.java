package eu.codedsakura.mods.mixin;

import com.mojang.authlib.GameProfile;
import eu.codedsakura.fabrictpa.FabricTPA;
import eu.codedsakura.fabrictpa.IStoreHome;
import eu.codedsakura.fabrictpa.WorldCoordinate;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerMixin implements IStoreHome {

    private static final String MOD_ID = "quickhomes";
    @Unique
    public Map<String, WorldCoordinate> worldCoordinateMap = new HashMap<>();

    public WorldCoordinate oldWorldCoordinate = null;

    private static final Map<String, RegistryKey<World>> worldMap = new HashMap<>();

    static {
        worldMap.put("OVERWORLD", World.OVERWORLD);
        worldMap.put("END", World.END);
        worldMap.put("NETHER", World.NETHER);
    }

    @Inject(at = @At("RETURN"), method = "readCustomDataFromNbt(Lnet/minecraft/nbt/NbtCompound;)V")
    public void readCustomDataFromNbt(NbtCompound tag, CallbackInfo c) {
        NbtCompound oldNbt = null;
        if (tag.contains(FabricTPA.QUICK_BACK)) {
            oldNbt = tag.getCompound(FabricTPA.QUICK_BACK);
        } else if (tag.contains("PlayerPersisted") && tag.getCompound("PlayerPersisted").contains(FabricTPA.QUICK_BACK)) {
            oldNbt = tag.getCompound("PlayerPersisted").getCompound(FabricTPA.QUICK_BACK);
        }

        if (oldNbt != null
                && oldNbt.contains("world")
                && oldNbt.contains("x")
                && oldNbt.contains("y")
                && oldNbt.contains("z")
                && oldNbt.contains("yaw")
                && oldNbt.contains("pitch")) {
            ServerWorld world = ((ServerPlayerEntity) (Object) this).getServerWorld().getServer().getWorld(worldMap.getOrDefault(oldNbt.getString("world"), World.OVERWORLD));
            this.oldWorldCoordinate = new WorldCoordinate(world, oldNbt.getDouble("x"), oldNbt.getDouble("y"),
                    oldNbt.getDouble("z"),
                    oldNbt.getFloat("yaw"),
                    oldNbt.getFloat("pitch"));
        }


        NbtCompound data = null;
        if (tag.contains(MOD_ID)) {
            data = tag.getCompound(MOD_ID);
        } else if (tag.contains("PlayerPersisted") && tag.getCompound("PlayerPersisted").contains(MOD_ID)) {
            data = tag.getCompound("PlayerPersisted").getCompound(MOD_ID);
        }
        if (data == null || data.getKeys().isEmpty()) return;
        for (String homeName : data.getKeys()) {
            NbtCompound worldNbt = data.getCompound(homeName);
            if (worldNbt.contains("world")
                    && worldNbt.contains("x")
                    && worldNbt.contains("y")
                    && worldNbt.contains("z")
                    && worldNbt.contains("yaw")
                    && worldNbt.contains("pitch")) {
                ServerWorld world = ((ServerPlayerEntity) (Object) this).getServerWorld().getServer().getWorld(worldMap.getOrDefault(worldNbt.getString("world"), World.OVERWORLD));
                this.worldCoordinateMap.put(homeName, new WorldCoordinate(world, worldNbt.getDouble("x"), worldNbt.getDouble("y"),
                        worldNbt.getDouble("z"),
                        worldNbt.getFloat("yaw"),
                        worldNbt.getFloat("pitch")));
            }
        }

    }

    @Inject(at = @At("RETURN"), method = "writeCustomDataToNbt(Lnet/minecraft/nbt/NbtCompound;)V")
    public void writeCustomDataToNbt(NbtCompound tag, CallbackInfo c) {
        if (!worldCoordinateMap.isEmpty()) {
            NbtCompound data = new NbtCompound();
            worldCoordinateMap.forEach((homeName, worldCoordinate) -> {
                data.put(homeName, convertNbt(worldCoordinate));
            });
            tag.put(MOD_ID, data);
        }
        if (oldWorldCoordinate != null) {
            tag.put(FabricTPA.QUICK_BACK, convertNbt(oldWorldCoordinate));
        }
    }

    private NbtCompound convertNbt(WorldCoordinate worldCoordinate) {
        NbtCompound worldNbt = new NbtCompound();
        String world = worldMap.entrySet().stream()
                .filter(w -> w.getValue().equals(worldCoordinate.getTargetWorld().getRegistryKey()))
                .findFirst().map(Map.Entry::getKey).orElse("OVERWORLD");
        worldNbt.putString("world", world);
        worldNbt.putDouble("x", worldCoordinate.getX());
        worldNbt.putDouble("y", worldCoordinate.getY());
        worldNbt.putDouble("z", worldCoordinate.getZ());
        worldNbt.putFloat("yaw", worldCoordinate.getYaw());
        worldNbt.putFloat("pitch", worldCoordinate.getPitch());
        return worldNbt;
    }

    @Inject(at = @At("RETURN"), method = "copyFrom(Lnet/minecraft/server/network/ServerPlayerEntity;Z)V")
    public void copyFrom(ServerPlayerEntity serverPlayer, boolean bl, CallbackInfo c) {
        ServerPlayerMixin serverPlayerMixin = ((ServerPlayerMixin)((Object) serverPlayer));
        worldCoordinateMap = serverPlayerMixin.worldCoordinateMap;
        oldWorldCoordinate = serverPlayerMixin.oldWorldCoordinate;
    }

    @Override
    public void setOldWorldCoordinate(WorldCoordinate worldCoordinate) {
        this.oldWorldCoordinate = worldCoordinate;
    }

    @Override
    public WorldCoordinate getOldWorldCoordinate() {
        return this.oldWorldCoordinate;
    }

    @Override
    public int setHome(WorldCoordinate worldCoordinate, String homeName) {
        if (worldCoordinateMap.size() >=3) {
            return 0;
        }
        this.worldCoordinateMap.put(homeName, worldCoordinate);
        return 1;
    }

    @Override
    public WorldCoordinate getHome(String homeName) {
        if (!this.worldCoordinateMap.containsKey(homeName)) {
            return null;
        }
        return this.worldCoordinateMap.get(homeName);
    }

    @Override
    public int delHome(String homeName) {
        if (!this.worldCoordinateMap.containsKey(homeName)) {
            return 0;
        }
        this.worldCoordinateMap.remove(homeName);
        return 1;
    }

    @Override
    public List<String> getHomeNames() {
        return worldCoordinateMap.keySet().stream().toList();
    }
}
