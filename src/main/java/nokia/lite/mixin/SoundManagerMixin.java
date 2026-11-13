package nokia.lite.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import nokia.lite.Skins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** XP pickup and player hit sounds: plays the chosen file instead (they arrive as sound packets, not level events). */
@Mixin(SoundManager.class)
public class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void nokialite$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (Skins.handleSound(sound)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}
}
