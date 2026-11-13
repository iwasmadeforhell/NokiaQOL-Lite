package nokia.lite.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import nokia.lite.Skins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the break burst and the cracking particles of ore groups whose particles are switched off. */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void nokialite$destroy(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (Skins.hideParticles(state)) ci.cancel();
	}

	@Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
	private void nokialite$crack(BlockPos pos, Direction side, CallbackInfo ci) {
		if (Skins.hideParticles(((ClientLevel) (Object) this).getBlockState(pos))) ci.cancel();
	}
}
