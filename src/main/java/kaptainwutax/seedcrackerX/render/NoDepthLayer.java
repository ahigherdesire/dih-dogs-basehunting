package kaptainwutax.seedcrackerX.render;

import com.autism.seedcracker.compat.EspRenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;

/** SeedCrackerX's see-through line layer, backed by DIH's see-through ESP lines. */
public final class NoDepthLayer {
    private NoDepthLayer() {
    }

    public static final RenderType LINES_NO_DEPTH_LAYER = EspRenderTypes.LINES_SEE_THROUGH;
}
