package dev.rlcraft.ice.optimizer.render.terrain;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Raw bindings are restored exactly, leaving Minecraft's cached state unchanged. */
final class NativeTerrainTextureState {
    private final int active, atlas;
    private int bound;
    NativeTerrainTextureState() {
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        atlas=bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }
    void bind(int texture) {
        if(texture<0)texture=atlas;
        if(texture!=bound){GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);bound=texture;}
    }
    void close(){if(bound!=atlas)GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);GL13.glActiveTexture(active);}
}
