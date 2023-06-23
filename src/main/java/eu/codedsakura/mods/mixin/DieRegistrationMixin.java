package eu.codedsakura.mods.mixin;

import eu.codedsakura.mods.callback.DieRegistrationCallback;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class DieRegistrationMixin {

    @Inject(at = @At("HEAD"), method = "onDeath")
    private void onDie(final DamageSource damageSource, CallbackInfo info) {
        DieRegistrationCallback.EVENT.invoker().register((ServerPlayerEntity) (Object) this);
    }
}
