package de.johni0702.minecraft.gui.versions.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.johni0702.minecraft.gui.versions.callbacks.MouseCallback;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forge port: Forge replaces the direct Screen#mouseClicked/mouseReleased/mouseDragged calls in MouseHandler with its
 * own hooks (which fire Forge's events and then call the screen), so we wrap those hooks instead.
 * Screen#mouseScrolled is still called directly.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseListener {
    @WrapOperation(method = "onButton", require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/event/ForgeEventFactoryClient;onScreenMouseClicked(Lnet/minecraft/client/gui/screens/Screen;DDLnet/minecraft/client/input/MouseButtonEvent;Z)Z"))
    private boolean mouseDown(Screen screen, double mouseX, double mouseY, MouseButtonEvent click, boolean doubled, Operation<Boolean> original) {
        if (MouseCallback.EVENT.invoker().mouseDown(new de.johni0702.minecraft.gui.function.Click(click))) {
            return true;
        }
        return original.call(screen, mouseX, mouseY, click, doubled);
    }

    @WrapOperation(method = "onButton", require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/event/ForgeEventFactoryClient;onScreenMouseReleased(Lnet/minecraft/client/gui/screens/Screen;DDLnet/minecraft/client/input/MouseButtonEvent;)Z"))
    private boolean mouseUp(Screen screen, double mouseX, double mouseY, MouseButtonEvent click, Operation<Boolean> original) {
        if (MouseCallback.EVENT.invoker().mouseUp(new de.johni0702.minecraft.gui.function.Click(click))) {
            return true;
        }
        return original.call(screen, mouseX, mouseY, click);
    }

    @WrapOperation(method = "handleAccumulatedMovement", require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/ForgeHooksClient;onScreenMouseDrag(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/MouseButtonEvent;DD)Z"))
    private boolean mouseDrag(Screen screen, MouseButtonEvent click, double dx, double dy, Operation<Boolean> original) {
        if (MouseCallback.EVENT.invoker().mouseDrag(new de.johni0702.minecraft.gui.function.Click(click), dx, dy)) {
            return true;
        }
        return original.call(screen, click, dx, dy);
    }

    @WrapOperation(method = "onScroll", require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
    private boolean mouseScroll(Screen screen, double x, double y, double horizontal, double vertical, Operation<Boolean> original) {
        if (MouseCallback.EVENT.invoker().mouseScroll(x, y, horizontal, vertical)) {
            return true;
        }
        return original.call(screen, x, y, horizontal, vertical);
    }
}
