package com.replaymod.core.forge;

import com.replaymod.core.mixin.ScreenWidgetsAccessor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * Replacement for Fabric API's {@code Screens.getButtons}: a live, mutable view of all widgets of a screen.
 */
public final class ForgeScreens {
    private ForgeScreens() {}

    public static List<AbstractWidget> getButtons(Screen screen) {
        return new ButtonList((ScreenWidgetsAccessor) screen);
    }

    private static final class ButtonList extends AbstractList<AbstractWidget> {
        private final List<Renderable> renderables;
        private final List<GuiEventListener> children;
        private final List<NarratableEntry> narratables;

        private ButtonList(ScreenWidgetsAccessor screen) {
            this.renderables = screen.replaymod$getRenderables();
            this.children = screen.replaymod$getChildren();
            this.narratables = screen.replaymod$getNarratables();
        }

        /** Indices into {@link #renderables} of all widgets. */
        private List<Integer> indices() {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < renderables.size(); i++) {
                if (renderables.get(i) instanceof AbstractWidget) {
                    result.add(i);
                }
            }
            return result;
        }

        @Override
        public AbstractWidget get(int index) {
            return (AbstractWidget) renderables.get(indices().get(index));
        }

        @Override
        public int size() {
            return indices().size();
        }

        @Override
        public void add(int index, AbstractWidget element) {
            List<Integer> indices = indices();
            if (index < 0 || index > indices.size()) {
                throw new IndexOutOfBoundsException(index);
            }
            if (index == indices.size()) {
                renderables.add(element);
                children.add(element);
                narratables.add(element);
                return;
            }
            AbstractWidget successor = (AbstractWidget) renderables.get(indices.get(index));
            renderables.add(indices.get(index), element);
            int childIndex = children.indexOf(successor);
            children.add(childIndex < 0 ? children.size() : childIndex, element);
            int narratableIndex = narratables.indexOf(successor);
            narratables.add(narratableIndex < 0 ? narratables.size() : narratableIndex, element);
        }

        @Override
        public AbstractWidget set(int index, AbstractWidget element) {
            AbstractWidget old = remove(index);
            add(index, element);
            return old;
        }

        @Override
        public AbstractWidget remove(int index) {
            AbstractWidget old = get(index);
            renderables.remove(old);
            children.remove(old);
            narratables.remove(old);
            return old;
        }
    }
}
