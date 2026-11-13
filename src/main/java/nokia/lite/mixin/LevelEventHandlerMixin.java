package nokia.lite.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import nokia.lite.Skins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block-break level event (2001): plays the ore skin's own break sound instead of the game's. */
@Mixin(LevelEventHandler.class)
public class LevelEventHandlerMixin {
	@Unique private int nokialite$data;

	@Inject(method = "levelEvent", at = @At("HEAD"))
	private void nokialite$data(int type, BlockPos pos, int data, CallbackInfo ci) {
		nokialite$data = data;
	}

	@WrapOperation(
		method = "levelEvent",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"),
		slice = @Slice(
			from = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;stateById(I)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 0),
			to = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;addDestroyBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", ordinal = 0)
		)
	)
	private void nokialite$breakSound(ClientLevel level, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay, Operation<Void> original) {
		if (!Skins.handleBreak(Block.stateById(nokialite$data), pos)) original.call(level, pos, sound, source, volume, pitch, distanceDelay);
	}
}
