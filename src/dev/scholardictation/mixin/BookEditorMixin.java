package dev.scholardictation.mixin;

import dev.scholardictation.DictationScreen;
import dev.scholardictation.SpeechSession;
import dev.scholardictation.MicrophoneSettings;
import io.github.mortuusars.scholar.client.gui.Widgets;
import io.github.mortuusars.scholar.client.gui.screen.edit.SpreadBookEditScreen;
import net.minecraft.client.gui.components.ImageButton;
import io.github.mortuusars.scholar.client.gui.widget.textbox.TextBox;
import io.github.mortuusars.scholar.client.gui.widget.textbox.text.FormattedStringEditor;
import io.github.mortuusars.scholar.client.gui.widget.textbox.text.FormattedString;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import java.io.IOException;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_GUI;
import static org.lwjgl.sdl.SDLKeycode.SDL_KMOD_LCTRL;
import static org.lwjgl.sdl.SDLKeycode.SDLK_V;
import static org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_V;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SpreadBookEditScreen.class, remap = false)
abstract class BookEditorMixin extends Screen implements DictationScreen {
    @Shadow protected TextBox leftPageTextBox;
    @Shadow protected TextBox rightPageTextBox;

    @Unique private static final int[] TAB_SOURCE_X = {0, 10, 10};
    @Unique private static final int[] TAB_SOURCE_Y = {436, 445, 445};
    @Unique private static final int[] TAB_WIDTHS = {10, 6, 13};
    @Unique private static final int[] TAB_HEIGHTS = {9, 5, 14};
    @Unique private static final long WAVE_FRAME_NANOS = 180_000_000L;

    @Unique private static final Identifier[] SCHOLAR_DICTATION_WAVES = {
        Identifier.fromNamespaceAndPath("scholar_dictation", "book/dictation_wave_0"),
        Identifier.fromNamespaceAndPath("scholar_dictation", "book/dictation_wave_1"),
        Identifier.fromNamespaceAndPath("scholar_dictation", "book/dictation_wave_2")
    };

    @Unique private ImageButton scholarDictation$button;
    @Unique private SpeechSession scholarDictation$session;
    @Unique private long scholarDictation$animationStart;
    @Unique private TextBox scholarDictation$page;
    @Unique private String scholarDictation$original;
    @Unique private String scholarDictation$preview = "";
    @Unique private FormattedString scholarDictation$renderOriginal;
    @Unique private int scholarDictation$cursor;
    @Unique private int scholarDictation$anchor;
    @Unique private Component scholarDictation$status;
    @Unique private Component scholarDictation$tooltip;

    protected BookEditorMixin(Component title) {
        super(title);
    }

    @Inject(method = "createWidgets", at = @At("TAIL"))
    private void scholarDictation$addButton(CallbackInfo ci) {
        scholarDictation$close();
        int x = Math.max(0, leftPageTextBox.getX() - 46) + 1;
        int y = leftPageTextBox.getY() + 54;
        scholarDictation$button = addRenderableWidget(new ImageButton(x, y, 18, 18,
            Widgets.threeStateSprites(Identifier.fromNamespaceAndPath("scholar_dictation", "book/microphone")),
            _ -> scholarDictation$toggle()));
        scholarDictation$button.active = SpeechSession.supported();
        scholarDictation$tooltip = null;
        scholarDictation$updateButton();
    }

    @Inject(method = "extractBook", at = @At("TAIL"))
    private void scholarDictation$drawTab(GuiGraphicsExtractor graphics, int x, int y, float delta, CallbackInfo ci) {
        if (scholarDictation$button == null) return;
        var book = (SpreadBookEditScreen) (Object) this;
        int tabX = scholarDictation$button.getX() - 6;
        int tabY = scholarDictation$button.getY() - 5;
        // Stretch the blank leather interior from Scholar's own atlas, preserving its corners.
        Identifier texture = book.getTexture();
        int tint = book.getTintColor();
        int drawY = tabY;
        for (int row = 0; row < TAB_HEIGHTS.length; row++) {
            int drawX = tabX;
            for (int col = 0; col < TAB_WIDTHS.length; col++) {
                int tileWidth = TAB_WIDTHS[col];
                int tileHeight = TAB_HEIGHTS[row];
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, drawX, drawY,
                    TAB_SOURCE_X[col], TAB_SOURCE_Y[row], tileWidth, tileHeight,
                    col == 1 ? 1 : tileWidth, row == 1 ? 1 : tileHeight, 512, 512, tint);
                drawX += tileWidth;
            }
            drawY += TAB_HEIGHTS[row];
        }
        if (scholarDictation$session != null) {
            int frame = (int) ((System.nanoTime() - scholarDictation$animationStart)
                / WAVE_FRAME_NANOS % (SCHOLAR_DICTATION_WAVES.length + 1));
            if (frame < SCHOLAR_DICTATION_WAVES.length) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCHOLAR_DICTATION_WAVES[frame], tabX - 8, tabY - 9, 37, 37);
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void scholarDictation$keepPageFocus(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> ci) {
        var button = scholarDictation$button;
        if (button != null && event.button() == 0 && button.isMouseOver(event.x(), event.y())) {
            if (button.active && !doubleClick) scholarDictation$toggle();
            ci.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = false)
    private void scholarDictation$handleShortcut(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        if (scholarDictation$button == null || !scholarDictation$button.active || event.hasAltDown()) return;

        boolean commandDown = (event.modifiers() & SDL_KMOD_GUI) != 0;
        boolean mac = net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.OSX;
        boolean dictationKey = mac
            ? event.shortcutKey() == SDLK_V && !event.hasShiftDown()
            : event.shortcutKey() == org.lwjgl.sdl.SDLKeycode.SDLK_D && event.hasShiftDown();
        if (event.hasControlDown() && !commandDown && dictationKey) {
            if (scholarDictation$session == null) scholarDictation$toggle();
            ci.setReturnValue(true);
        } else if (mac && event.shortcutKey() == SDLK_V && commandDown && !event.hasControlDown() && getFocused() instanceof TextBox page
            && (page == leftPageTextBox || page == rightPageTextBox)) {
            // Scholar's editor handles paste with Control and uses Shift to strip formatting.
            int pasteModifiers = (event.modifiers() & ~SDL_KMOD_GUI) | SDL_KMOD_LCTRL;
            if (page.keyPressed(new KeyEvent(SDL_SCANCODE_V, event.keycode(), pasteModifiers))) {
                ci.setReturnValue(true);
            }
        }
    }

    @Inject(method = "setTextBoxes(Z)V", at = @At("HEAD"))
    private void scholarDictation$pageChanged(boolean resetCursor, CallbackInfo ci) {
        scholarDictation$close();
    }

    @Override
    public void removed() {
        scholarDictation$close();
        super.removed();
    }

    @Override
    public void scholarDictation$tick() {
        if (scholarDictation$session != null) {
            var editor = scholarDictation$page.getEditor();
            if ((getFocused() instanceof TextBox && getFocused() != scholarDictation$page)
                || editor.getCursorPos() != scholarDictation$cursor
                || editor.getSelectionAnchor() != scholarDictation$anchor
                || !editor.getString().toString().equals(scholarDictation$original)) {
                scholarDictation$status = Component.translatable("gui.scholar_dictation.cancelled");
                scholarDictation$close();
            } else {
                try {
                    var result = scholarDictation$session.poll();
                    if (result != null) {
                        var page = scholarDictation$page;
                        scholarDictation$close();
                        if (result.error() != null) {
                            scholarDictation$showError(result.error());
                        } else {
                            scholarDictation$insert(page, editor, result.text(), true);
                        }
                    } else {
                        scholarDictation$preview = scholarDictation$session.preview();
                    }
                } catch (IOException e) {
                    scholarDictation$close();
                    scholarDictation$showError(e.getMessage());
                }
            }
        }
        scholarDictation$updateButton();
    }

    @Unique
    private void scholarDictation$toggle() {
        if (scholarDictation$session != null) {
            try {
                if (scholarDictation$session.state() == SpeechSession.State.LISTENING) {
                    scholarDictation$session.stop();
                }
            } catch (IOException e) {
                scholarDictation$close();
                scholarDictation$showError(e.getMessage());
            }
        } else {
            TextBox page;
            if (getFocused() == leftPageTextBox || getFocused() == rightPageTextBox) {
                page = (TextBox) getFocused();
            } else {
                page = rightPageTextBox.getEditor().length() > 0 ? rightPageTextBox : leftPageTextBox;
                page.getEditor().setCursorPos(page.getEditor().length(), false);
                setFocused(page);
                ((TextBoxAccess) page).scholarDictation$refresh();
            }
            try {
                scholarDictation$status = null;
                scholarDictation$session = SpeechSession.open("record", MicrophoneSettings.selected().id());
                scholarDictation$animationStart = System.nanoTime();
                scholarDictation$page = page;
                var editor = page.getEditor();
                scholarDictation$original = editor.getString().toString();
                scholarDictation$cursor = editor.getCursorPos();
                scholarDictation$anchor = editor.getSelectionAnchor();
            } catch (IOException e) {
                scholarDictation$showError(e.getMessage());
            }
        }
        scholarDictation$updateButton();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), remap = false)
    private void scholarDictation$renderPreview(GuiGraphicsExtractor graphics, int x, int y, float delta, CallbackInfo ci) {
        if (scholarDictation$preview.isBlank() || scholarDictation$page == null) return;
        var editor = scholarDictation$page.getEditor();
        if (editor.getCursorPos() != scholarDictation$cursor || editor.getSelectionAnchor() != scholarDictation$anchor
            || !editor.getString().toString().equals(scholarDictation$original)) return;
        scholarDictation$renderOriginal = editor.getString();
        editor.setString(new FormattedString(scholarDictation$renderOriginal));
        scholarDictation$insert(scholarDictation$page, editor, scholarDictation$preview, false);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"), remap = false)
    private void scholarDictation$restoreEditor(GuiGraphicsExtractor graphics, int x, int y, float delta, CallbackInfo ci) {
        if (scholarDictation$renderOriginal == null) return;
        var editor = scholarDictation$page.getEditor();
        editor.setString(scholarDictation$renderOriginal);
        editor.setSelectionRange(scholarDictation$anchor, scholarDictation$cursor);
        scholarDictation$renderOriginal = null;
        ((TextBoxAccess) scholarDictation$page).scholarDictation$refresh();
    }

    @Unique
    private void scholarDictation$insert(TextBox page, FormattedStringEditor editor, String transcript, boolean commit) {
        String text = transcript.replace("§", "").replaceAll("[\\p{Cc}&&[^\\n]]", "").strip();
        if (text.isEmpty()) {
            if (commit) scholarDictation$status = Component.translatable("gui.scholar_dictation.empty");
            return;
        }
        int start = editor.isSelecting() ? editor.getSelectionStart() : editor.getCursorPos();
        int end = editor.isSelecting() ? editor.getSelectionEnd() : start;
        // Scholar's editor indexes its formatted characters, which can differ from UTF-16 offsets.
        String prefix = editor.getString().subString(0, start).toStringWithoutFormatting();
        String suffix = editor.getString().subString(end, editor.length()).toStringWithoutFormatting();
        if (end == editor.length() && !prefix.isEmpty()
            && !Character.isWhitespace(prefix.codePointBefore(prefix.length()))
            && Character.isLetterOrDigit(text.codePointAt(0))) {
            text = " " + text;
        }
        if (!editor.getValidator().test(prefix + text + suffix)) {
            if (commit) scholarDictation$status = Component.translatable("gui.scholar_dictation.full");
            return;
        }
        if (editor.insertTextAtCursor(text)) {
            if (commit) page.onTextChanged().accept(editor.getString());
            ((TextBoxAccess) page).scholarDictation$refresh();
        } else {
            if (commit) scholarDictation$status = Component.translatable("gui.scholar_dictation.full");
        }
    }

    @Unique
    private void scholarDictation$updateButton() {
        if (scholarDictation$button == null) return;
        SpeechSession.State state = scholarDictation$session == null ? null : scholarDictation$session.state();
        String label;
        if (!scholarDictation$button.active) {
            label = "unavailable";
        } else if (state != null) {
            label = switch (state) {
                case STARTING -> "starting";
                case LISTENING -> "stop";
                case FINISHING -> "finish";
            };
        } else {
            label = "start";
        }
        Component tooltip = scholarDictation$status != null ? scholarDictation$status
            : Component.translatable("gui.scholar_dictation." + label);
        if (scholarDictation$status == null && label.equals("start")) {
            tooltip = tooltip.copy().append(Component.literal(net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.OSX ? " Ctrl+V" : " Ctrl+Shift+D").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!tooltip.equals(scholarDictation$tooltip)) {
            scholarDictation$tooltip = tooltip;
            scholarDictation$button.setMessage(tooltip);
            scholarDictation$button.setTooltip(Tooltip.create(tooltip));
        }
    }

    @Unique
    private void scholarDictation$showError(String message) {
        scholarDictation$status = Component.translatable("gui.scholar_dictation.error", message);
        com.mojang.logging.LogUtils.getLogger().warn("Scholar Dictation: {}", message);
    }

    @Unique
    private void scholarDictation$close() {
        if (scholarDictation$session != null) {
            scholarDictation$session.close();
            scholarDictation$session = null;
            scholarDictation$page = null;
            scholarDictation$original = null;
            scholarDictation$preview = "";
        }
    }
}
