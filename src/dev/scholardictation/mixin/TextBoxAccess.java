package dev.scholardictation.mixin;

import io.github.mortuusars.scholar.client.gui.widget.textbox.TextBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = TextBox.class, remap = false)
public interface TextBoxAccess {
    @Invoker("refreshDisplayCache")
    void scholarDictation$refresh();
}
