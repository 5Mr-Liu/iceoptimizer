package dev.rlcraft.ice.benchmark;

import com.mojang.authlib.GameProfile;
import java.io.PrintWriter;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;

/** Test-only camera: entities cannot push the measured view off its route. */
final class FixedRenderCamera {
    private EntityOtherPlayerMP camera;
    // Natural saved-world view used by the preceding fixed-yaw fixtures.
    private static final double X=513.409499859426, Y=76.82575536878666, Z=411.15974329186395;

    void update(Minecraft mc, float yaw, float pitch, float routeYaw, double seconds, boolean moving) {
        if(camera==null || camera.world!=mc.world) {
            camera=new EntityOtherPlayerMP(mc.world,new GameProfile(new UUID(0x49434542454e4348L,2),"ICECamera"));
            camera.capabilities.isFlying=mc.player.capabilities.isFlying;
            camera.capabilities.allowFlying=mc.player.capabilities.allowFlying;
        }
        double distance=moving?1.5*Math.sin(seconds*Math.PI/2):0;
        double angle=routeYaw*Math.PI/180;
        camera.setPosition(X-Math.sin(angle)*distance,Y,Z+Math.cos(angle)*distance);
        camera.prevPosX=camera.lastTickPosX=camera.posX;
        camera.prevPosY=camera.lastTickPosY=camera.posY;
        camera.prevPosZ=camera.lastTickPosZ=camera.posZ;
        camera.prevRotationYaw=camera.rotationYaw=yaw;
        camera.prevRotationPitch=camera.rotationPitch=pitch;
        camera.rotationYawHead=camera.prevRotationYawHead=yaw;
        mc.setRenderViewEntity(camera);
    }

    void summary(PrintWriter out) {
        out.println("render_camera_path=fixed-natural-v1-amplitude1.5-period4");
        out.println("render_camera_anchor="+X+","+Y+","+Z);
        out.println("render_camera_position="+camera.posX+","+camera.posY+","+camera.posZ);
        out.println("render_camera_angles="+camera.rotationYaw+","+camera.rotationPitch);
    }
}
