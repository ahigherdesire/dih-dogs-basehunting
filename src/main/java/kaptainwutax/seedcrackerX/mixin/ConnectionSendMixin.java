package kaptainwutax.seedcrackerX.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.autism.seedcracker.util.tunnel.SilentRotation;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;

/** Routes the addon's optional silent facing through its existing DIH move-packet accessor. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ConnectionSendMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> seedcracker$rewriteRotation(Packet<?> packet) {
        return SilentRotation.processPacket(packet);
    }
}
