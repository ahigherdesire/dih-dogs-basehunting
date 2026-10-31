package com.autism.seedcracker.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dihclient.render.mc.DihRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * ESP render types + line helper. The see-through LINES / QUADS types come from DIH Client itself, so ESP from
 * this addon draws exactly like DIH's own storage ESP on every supported Minecraft version.
 */
public final class EspRenderTypes {
    private EspRenderTypes() {}

    /** See-through line render type, shared with DIH's own storage ESP (always-pass depth). */
    public static final RenderType LINES_SEE_THROUGH = DihRenderTypes.storageEspLinesSeeThrough();

    /** See-through quad fill render type, shared with DIH's own storage ESP. */
    public static final RenderType FILL_SEE_THROUGH = DihRenderTypes.storageEspFillSeeThrough();

    /** Emit a world-space line into a LINES buffer (was AutismWorldGeometry.line). The LINES
     * pipeline requires position + color + normal + lineWidth on every vertex - omitting any of
     * them crashes with "Missing elements in vertex". The normal is the line's direction. */
    public static void line(PoseStack.Pose pose, VertexConsumer buffer,
                            double x1, double y1, double z1, double x2, double y2, double z2,
                            int color, float width) {
        float nx = (float) (x2 - x1), ny = (float) (y2 - y1), nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0e-6) { nx /= len; ny /= len; nz /= len; } else { nx = 0; ny = 1; nz = 0; }
        final float fnx = nx, fny = ny, fnz = nz;
        org.joml.Vector3f normal = new org.joml.Vector3f(fnx, fny, fnz);
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }
}
