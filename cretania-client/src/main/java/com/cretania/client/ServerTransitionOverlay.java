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
 * Solo aplica en multijugador: en un mundo singleplayer (o abierto a LAN) no se muestra.
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

    /**
     * True desde que arranca la conexión a un servidor hasta que se vuelve a un menú.
     * Durante el "Connecting / Joining world…" todavía no hay jugador ni mundo, así que
     * no sirve mirar mc.player para saber si el overlay debe pintarse.
     */
    private static boolean sessionActive = false;

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

    /** Oculta el overlay al instante. */
    private static void hide() {
        state = 0;
    }

    /** True si estamos en un mundo local (singleplayer o abierto a LAN). */
    private static boolean isSingleplayer() {
        return Minecraft.getInstance().hasSingleplayerServer();
    }

    private static float currentAlpha() {
        if (state == 0) return 0f;
        long elapsed = System.currentTimeMillis() - stateStartMs;
        switch (state) {
            case 1:
                // Al terminar el fade-in hay que pasar a hold. Sin esto el estado 1 no
                // tenía salida y el overlay se quedaba a alpha 1 para siempre.
                if (elapsed >= FADE_IN_MS) {
                    enterState(2, holdDurationMs);
                    return 1f;
                }
                return elapsed / (float) FADE_IN_MS;
            case 2:
                if (elapsed >= holdDurationMs) enterState(3, 0);
                return 1f;
            case 3:
                float a = Math.max(0f, 1f - elapsed / (float) FADE_OUT_MS);
                if (a <= 0f) state = 0;
                return a;
            default:
                state = 0;
                return 0f;
        }
    }

    // ── Detección de transiciones ─────────────────────────────────────────────

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (isSingleplayer()) {
            sessionActive = false;
            hide();
            return;
        }
        // Primer login al servidor o cambio de server. Con Velocity el cambio de backend no
        // reabre ConnectScreen: llega directamente otro LoggingIn, y este es el único aviso.
        sessionActive = true;
        enterState(2, HOLD_MAX_MS);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Desconexión real: volvemos al menú, hay que ocultar. Con Velocity el cambio de
        // servidor NO dispara este evento (la conexión al proxy se mantiene y llega otro
        // LoggingIn), así que aquí nunca hace falta mostrar nada.
        sessionActive = false;
        hide();
    }

    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        if (state != 2) return;
        String name = event.getScreen().getClass().getSimpleName();
        // Terreno ya recibido → dejar un extra corto y fundir a salida.
        // OJO: NO incluir ConnectScreen. Se cierra cuando ARRANCA la carga de terreno, no
        // cuando termina; acortar el hold ahí destaparía justo la parte que queremos cubrir.
        if (name.contains("ReceivingLevel") || name.contains("LevelLoading")) {
            enterState(2, 600L);
        }
    }

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        String name = event.getScreen().getClass().getSimpleName();

        // Arranca la conexión a un servidor: cubrir desde ya el "Connecting… / Joining
        // world…" de vanilla, que ocurre antes de que exista jugador y mundo.
        if (!isSingleplayer() && name.contains("Connect") && !name.contains("Disconnect")) {
            sessionActive = true;
            enterState(2, HOLD_MAX_MS);
            return;
        }

        // Pantallas de menú: fin de sesión, ocultar de inmediato (un fade-out encima
        // taparía la UI). Al desconectar se vuelve a JoinMultiplayerScreen, no a Title.
        if (name.contains("Title") || name.contains("MainMenu") || name.contains("Disconnect")
                || name.contains("Multiplayer") || name.contains("Realms")
                || name.contains("SelectWorld")) {
            sessionActive = false;
            hide();
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
        if (state == 0) return;

        // Fuera de una sesión de multijugador el overlay no pinta nada y taparía la UI.
        // No se puede usar mc.player aquí: durante la conexión todavía es null y es
        // justamente cuando hay que cubrir.
        if (!sessionActive || isSingleplayer()) {
            hide();
            return;
        }

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

        // Subtítulo de estado en la parte inferior. Hasta que existe el jugador seguimos
        // en la fase de conexión; a partir de ahí el cliente ya está recibiendo el mundo.
        int subAlpha = (int) (alpha * 200f);
        if (subAlpha > 5) {
            String sub = mc.player == null ? "Conectando al servidor…" : "Cargando mundo…";
            g.drawCenteredString(mc.font, sub, w / 2, h - 30,
                    (subAlpha << 24) | 0xCCCCCC);
        }
    }
}
