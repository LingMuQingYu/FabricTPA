package eu.codedsakura.fabrictpa;

import net.minecraft.server.world.ServerWorld;

public class WorldCoordinate {
    net.minecraft.server.world.ServerWorld targetWorld;
    double x;
    double y;
    double z;
    float yaw;
    float pitch;

    public WorldCoordinate(net.minecraft.server.world.ServerWorld targetWorld, double x, double y, double z,
                           float yaw, float pitch) {
        this.targetWorld = targetWorld;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public ServerWorld getTargetWorld() {
        return targetWorld;
    }

    public void setTargetWorld(ServerWorld targetWorld) {
        this.targetWorld = targetWorld;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }
}