package com.cretania.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Overlay de carga "Cretania Aeronautics" — cubre:
 *  1. La pantalla "Logging in…" y "Downloading terrain" del primer login al server.
 *  2. La transición entre servidores (lobby → survival, etc.).
 *  3. La espera de CLIENT_AUTH/hasJoined (típicamente 1-3 s) que ahora se hace tras
 *     el spawn (Velocity ya no fetchea Mojang en el handshake).
 *
 * Renderiza:
 *  - Imagen fullscreen `assets/cretania_client/textures/gui/loading_background.png`
 *    (recomendado 1920x1080 PNG, se estira a la ventana).
 *  - Si la imagen no existe, fallback a fondo negro con texto "CRETANIA".
 *
 * Estados:
 *  0 inactivo | 1 fade-in | 2 hold | 3 fade-out
 *
 * Usa System.currentTimeMillis para que las animaciones avancen aunque el tick game
 * esté pausado (típico durante login / carga de terreno).
 */
@EventBusSubscriber(modid = CretaniaClientMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ServerTransitionOverlay {

    private static final ResourceLocation BG_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            CretaniaClientMod.MOD_ID, "textures/gui/loading_background.png");

    private static int  state           = 0;
    private static long stateStartMs    = 0;
    private static long holdDurationMs  = 0;

    /** Cache: ¿existe el asset de imagen? Se evalúa la primera vez y se cachea. */
    private static Boolean bgAssetExists = null;

    /** Duración máxima del hold durante login/transición (suficiente para CLIENT_AUTH + spawn). */
    private static final long HOLD_MAX_MS = 6_000L;
    private static final long FADE_IN_MS  = 250L;
    private static final long FADE_OUT_MS = 900L;

    // ── Helpers de tiempo ────────────────────────────────────────────────────

    private static void enterState(int newState, long holdMs) {
        state          = newState;
        stateStartMs   = System.currentTimeMillis();
        holdDurationMs = holdMs;
    }

    private static float currentAlpha() {
        if (state == 0) return 0f;
        long elapsed = System.currentTimeMillis() - stateStartMs;
        switch (state) {
            case 1: return Math.min(1f, elapsed / (float) FADE_IN_MS);
            case 2:
                if (elapsed >= holdDurationMs) enterState(3, 0);
                return 1f;
            case 3:
                float a = Math.max(0f, 1f - elapsed / (float) FADE_OUT_MS);
                if (a <= 0f) state = 0;
                return a;
            default: return 0f;
        }
    }

    // ── Detección de transiciones ─────────────────────────────────────────────

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Primer login al servidor o cambio de server: mostrar overlay full-screen
        // hasta que la pantalla de carga (Downloading terrain) se cierre.
        enterState(2, HOLD_MAX_MS);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        enterState(1, 0); // fade-in (cambio de server o desconexión)
    }

    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        if (state == 2) {
            String name = event.getScreen().getClass().getSimpleName();
            if (name.contains("Level") || name.contains("Receiving") || name.contains("Loading")
                    || name.contains("Connect")) {
                // Mundo cargado: pequeño extra para que el spawn termine de cargar chunks
                enterState(2, 600L);
            }
        }
    }

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (state != 0) {
            String name = event.getScreen().getClass().getSimpleName();
            if (name.contains("Title") || name.contains("MainMenu") || name.contains("Disconnect")) {
                enterState(3, 0); // volver al menú: fade-out inmediato
            }
        }
    }

    // ── Render hooks ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        drawOverlay(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        drawOverlay(event.getGuiGraphics());
    }

    // ── Dibujo ────────────────────────────────────────────────────────────────

    private static void drawOverlay(GuiGraphics g) {
        float alpha = currentAlpha();
        if (alpha <= 0.005f) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // Detectar (una sola vez) si el asset de imagen existe
        if (bgAssetExists == null) {
            bgAssetExists = mc.getResourceManager().getResource(BG_TEXTURE).isPresent();
        }

        if (bgAssetExists) {
            // Imagen fullscreen con alpha
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            g.blit(BG_TEXTURE, 0, 0, 0, 0, w, h, w, h);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        } else {
            // Fallback: fondo negro con título Cretania
            int a = Math.min(255, (int) (alpha * 255));
            g.fill(0, 0, w, h, (a << 24) | 0x000000);
            g.pose().pushPose();
            g.pose().translate(w / 2f, h / 2f - 20f, 0f);
            g.pose().scale(2f, 2f, 1f);
            g.drawCenteredString(mc.font, "CRETANIA", 0, -4, (a << 24) | 0xFFFFFF);
            g.pose().popPose();
        }

        // Subtítulo de estado en la parte inferior
        int subAlpha = (int) (alpha * 200f);
        if (subAlpha > 5) {
            String sub = state == 2 ? "Cargando mundo…" : "Conectando al servidor…";
            g.drawCenteredString(mc.font, sub, w / 2, h - 30,
                    (subAlpha << 24) | 0xCCCCCC);
        }
    }
}
