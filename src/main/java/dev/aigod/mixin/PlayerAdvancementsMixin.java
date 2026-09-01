package dev.aigod.mixin;

import dev.aigod.AiGodMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow private ServerPlayer player;

    @Shadow public abstract AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @Inject(method = "award", at = @At("RETURN"))
    private void aiGod$onAward(AdvancementHolder advancement, String criterionKey,
                               CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        Optional<DisplayInfo> display = advancement.value().display();
        if (display.isEmpty() || !display.get().shouldAnnounceChat()) return;
        if (!getOrStartProgress(advancement).isDone()) return;
        AiGodMod.onAdvancement(player,
                display.get().getTitle().getString(),
                display.get().getDescription().getString());
    }
}
