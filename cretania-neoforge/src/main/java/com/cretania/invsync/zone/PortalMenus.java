package com.cretania.invsync.zone;

import com.cretania.invsync.zone.PortalManager.PortalDef;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Menús OP server-side (GUI de cofre + yunque — funciona con clientes vanilla).
 *
 * TODA la administración de portales se hace desde aquí, sin comandos:
 * - Menú principal (/portales): lista de portales + registrar portal nuevo.
 * - Menú de portal: asignar servidor destino, agregar/quitar servidores de la
 *   lista, renombrar, modo RTP (presets), coords destino y eliminar.
 *
 * Los campos de texto (nombres, coords) usan un menú de yunque: el OP escribe
 * en el campo de renombrar y confirma clickeando el ítem resultado.
 */
public class PortalMenus {

    private static final int MENU_SIZE = 54;

    // Menú principal
    private static final int SLOT_INFO   = 45;
    private static final int SLOT_CLOSE  = 49;
    private static final int SLOT_CREATE = 53;

    // Menú de portal (fila inferior)
    private static final int SLOT_BACK   = 45;
    private static final int SLOT_RENAME = 47;
    private static final int SLOT_MODE   = 49;
    private static final int SLOT_COORDS = 51;
    private static final int SLOT_DELETE = 53;

    /** Máximo de servidores mostrados (deja espacio para el botón "+ agregar"). */
    private static final int MAX_SERVER_SLOTS = 36;

    // -------------------------------------------------------------------------
    // Menú principal: lista de portales
    // -------------------------------------------------------------------------

    public static void openMain(ServerPlayer player) {
        List<PortalDef> portals = PortalManager.portalsSorted();
        SimpleContainer container = new SimpleContainer(MENU_SIZE);

        int max = Math.min(portals.size(), SLOT_INFO);
        for (int i = 0; i < max; i++) {
            PortalDef p = portals.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(text("Dimensión: " + p.dimension(), ChatFormatting.GRAY));
            lore.add(text(String.format("Bloques: (%d,%d,%d) - (%d,%d,%d)",
                    p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ()), ChatFormatting.GRAY));
            lore.add(text("Servidor: " + p.targetServer(), ChatFormatting.AQUA));
            lore.add(text("Modo: " + p.modeDescription(), ChatFormatting.GRAY));
            lore.add(text("Click para configurar", ChatFormatting.YELLOW));
            container.setItem(i, named(new ItemStack(Items.ENDER_EYE),
                    text(p.name(), ChatFormatting.LIGHT_PURPLE), lore));
        }

        container.setItem(SLOT_INFO, named(new ItemStack(Items.BOOK),
                text("Ayuda", ChatFormatting.GOLD),
                List.of(text("Construye un portal de obsidiana,", ChatFormatting.GRAY),
                        text("enciéndelo y usa el botón verde.", ChatFormatting.GRAY),
                        text("Todo se administra desde este menú;", ChatFormatting.GRAY),
                        text("los cambios aplican al instante.", ChatFormatting.GRAY),
                        text("Server 'local' = RTP aleatorio", ChatFormatting.DARK_GRAY),
                        text("dentro de este mismo server.", ChatFormatting.DARK_GRAY))));
        container.setItem(SLOT_CLOSE, named(new ItemStack(Items.BARRIER),
                text("Cerrar", ChatFormatting.RED), List.of()));
        container.setItem(SLOT_CREATE, named(new ItemStack(Items.EMERALD_BLOCK),
                text("Registrar portal aquí", ChatFormatting.GREEN),
                List.of(text("Registra el portal del Nether", ChatFormatting.GRAY),
                        text("más cercano a ti (radio 4).", ChatFormatting.GRAY),
                        text("Te pedirá el nombre en un yunque.", ChatFormatting.GRAY))));

        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new LockedChestMenu(id, inv, container) {
            @Override
            protected void onClick(int slot, ClickType type, ServerPlayer clicker) {
                if (slot >= 0 && slot < portals.size() && slot < SLOT_INFO) {
                    openPortal(clicker, portals.get(slot).name());
                } else if (slot == SLOT_CLOSE) {
                    clicker.closeContainer();
                } else if (slot == SLOT_CREATE) {
                    openTextInput(clicker, "Nombre del portal", "portal", PortalMenus::handleCreateInput);
                }
            }
        }, Component.literal("Portales Cretania")));
    }

    private static void handleCreateInput(ServerPlayer player, String rawName) {
        String name = sanitizeName(rawName);
        PortalDef def;
        if (name.isEmpty() || name.equals("portal")) {
            def = PortalManager.createAutoNamed(player); // nombre automático portal_N
        } else if (PortalManager.get(name) != null) {
            player.sendSystemMessage(text("Ya existe un portal llamado '" + name + "'.", ChatFormatting.RED));
            openMain(player);
            return;
        } else {
            def = PortalManager.createFromNearbyPortal(player, name);
        }

        if (def == null) {
            player.sendSystemMessage(text(
                    "No hay bloques de portal del Nether cerca. Construye y enciende el portal primero.",
                    ChatFormatting.RED));
            openMain(player);
        } else {
            player.sendSystemMessage(text(
                    "Portal '" + def.name() + "' registrado → " + def.targetServer(), ChatFormatting.GREEN));
            openPortal(player, def.name()); // directo a asignarle servidor
        }
    }

    // -------------------------------------------------------------------------
    // Menú de un portal: servidor destino / modo / coords / renombrar / eliminar
    // -------------------------------------------------------------------------

    public static void openPortal(ServerPlayer player, String portalName) {
        PortalDef portal = PortalManager.get(portalName);
        if (portal == null) {
            openMain(player);
            return;
        }

        List<String> servers = PortalManager.servers();
        SimpleContainer container = new SimpleContainer(MENU_SIZE);

        int serverCount = Math.min(servers.size(), MAX_SERVER_SLOTS);
        for (int i = 0; i < serverCount; i++) {
            String server = servers.get(i);
            boolean current = server.equalsIgnoreCase(portal.targetServer());
            ItemStack stack = new ItemStack(current ? Items.LIME_DYE : Items.PAPER);
            if (current) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            container.setItem(i, named(stack,
                    text(server, current ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    List.of(text(current ? "Servidor destino actual" : "Click: asignar en tiempo real",
                                    current ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                            text("Shift+Click: quitar de la lista", ChatFormatting.DARK_GRAY))));
        }
        int addSlot = serverCount; // botón "+" justo después del último servidor
        container.setItem(addSlot, named(new ItemStack(Items.NETHER_STAR),
                text("+ Agregar servidor", ChatFormatting.AQUA),
                List.of(text("Escribe el nombre del server", ChatFormatting.GRAY),
                        text("(el registrado en Velocity) y", ChatFormatting.GRAY),
                        text("se asigna a este portal.", ChatFormatting.GRAY),
                        text("Usa 'local' para RTP local.", ChatFormatting.DARK_GRAY))));

        container.setItem(SLOT_BACK, named(new ItemStack(Items.ARROW),
                text("Volver", ChatFormatting.WHITE), List.of()));
        container.setItem(SLOT_RENAME, named(new ItemStack(Items.NAME_TAG),
                text("Renombrar portal", ChatFormatting.WHITE),
                List.of(text("Nombre actual: " + portal.name(), ChatFormatting.GRAY))));
        container.setItem(SLOT_MODE, named(new ItemStack(Items.COMPASS),
                text("Modo destino: " + portal.modeDescription(), ChatFormatting.GOLD),
                List.of(text("Click: ciclar radio RTP", ChatFormatting.YELLOW),
                        text("off → 250 → 500 → 1000 → 2500", ChatFormatting.GRAY),
                        text("→ 5000 → 10000 → off", ChatFormatting.GRAY))));
        container.setItem(SLOT_COORDS, named(new ItemStack(Items.LODESTONE),
                text("Coords destino", ChatFormatting.GOLD),
                List.of(text(portal.hasTargetCoords()
                                ? String.format(Locale.US, "Actual: %.1f %.1f %.1f", portal.targetX(), portal.targetY(), portal.targetZ())
                                : "Actual: spawn del servidor", ChatFormatting.GRAY),
                        text("Click y escribe: x y z", ChatFormatting.YELLOW),
                        text("(opcional: x y z yaw pitch)", ChatFormatting.GRAY),
                        text("Escribe 'clear' para quitar.", ChatFormatting.GRAY))));
        container.setItem(SLOT_DELETE, named(new ItemStack(Items.TNT),
                text("Eliminar portal", ChatFormatting.RED),
                List.of(text("Shift+Click para eliminar", ChatFormatting.GRAY))));

        String name = portal.name();
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new LockedChestMenu(id, inv, container) {
            @Override
            protected void onClick(int slot, ClickType type, ServerPlayer clicker) {
                if (slot >= 0 && slot < serverCount) {
                    String server = servers.get(slot);
                    if (type == ClickType.QUICK_MOVE) {
                        // Shift+Click: quitar de la lista de servidores conocidos
                        PortalManager.removeServer(server);
                        clicker.sendSystemMessage(text("Servidor '" + server + "' quitado de la lista.", ChatFormatting.YELLOW));
                    } else {
                        PortalDef updated = PortalManager.setTargetServer(name, server);
                        if (updated != null) {
                            clicker.sendSystemMessage(text(
                                    "Portal '" + name + "' → " + updated.targetServer() + " (aplicado en tiempo real).",
                                    ChatFormatting.GREEN));
                        }
                    }
                    openPortal(clicker, name);
                } else if (slot == addSlot) {
                    openTextInput(clicker, "Nombre del servidor", "server",
                            (sp, text) -> handleAddServerInput(sp, name, text));
                } else if (slot == SLOT_BACK) {
                    openMain(clicker);
                } else if (slot == SLOT_RENAME) {
                    openTextInput(clicker, "Nuevo nombre", name,
                            (sp, text) -> handleRenameInput(sp, name, text));
                } else if (slot == SLOT_MODE) {
                    PortalManager.cycleRtpPreset(name);
                    openPortal(clicker, name);
                } else if (slot == SLOT_COORDS) {
                    openTextInput(clicker, "Coords: x y z (yaw pitch)",
                            portal.hasTargetCoords()
                                    ? String.format(Locale.US, "%.1f %.1f %.1f", portal.targetX(), portal.targetY(), portal.targetZ())
                                    : "x y z",
                            (sp, text) -> handleCoordsInput(sp, name, text));
                } else if (slot == SLOT_DELETE) {
                    if (type != ClickType.QUICK_MOVE) return; // requiere Shift+Click
                    PortalManager.delete(name);
                    clicker.sendSystemMessage(text("Portal '" + name + "' eliminado.", ChatFormatting.YELLOW));
                    openMain(clicker);
                }
            }
        }, Component.literal("Portal: " + portal.name())));
    }

    private static void handleAddServerInput(ServerPlayer player, String portalName, String rawServer) {
        String server = sanitizeName(rawServer);
        if (server.isEmpty() || server.equals("server")) {
            openPortal(player, portalName);
            return;
        }
        PortalManager.addServer(server);
        PortalDef updated = PortalManager.setTargetServer(portalName, server);
        if (updated != null) {
            player.sendSystemMessage(text(
                    "Servidor '" + server + "' agregado y asignado al portal '" + portalName + "'.",
                    ChatFormatting.GREEN));
        }
        openPortal(player, portalName);
    }

    private static void handleRenameInput(ServerPlayer player, String oldName, String rawName) {
        String newName = sanitizeName(rawName);
        if (newName.isEmpty() || newName.equalsIgnoreCase(oldName)) {
            openPortal(player, oldName);
            return;
        }
        PortalDef updated = PortalManager.rename(oldName, newName);
        if (updated == null) {
            player.sendSystemMessage(text("Ya existe un portal llamado '" + newName + "'.", ChatFormatting.RED));
            openPortal(player, oldName);
        } else {
            player.sendSystemMessage(text(
                    "Portal '" + oldName + "' renombrado a '" + newName + "'.", ChatFormatting.GREEN));
            openPortal(player, newName);
        }
    }

    private static void handleCoordsInput(ServerPlayer player, String portalName, String rawText) {
        String text = rawText.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("clear") || text.equals("-") || text.equals("x y z")) {
            PortalManager.setTargetCoords(portalName, null, null, null, null, null);
            player.sendSystemMessage(text("Portal '" + portalName + "': coords eliminadas (spawn default).",
                    ChatFormatting.YELLOW));
            openPortal(player, portalName);
            return;
        }

        String[] parts = text.split("[,\\s]+");
        if (parts.length != 3 && parts.length != 5) {
            player.sendSystemMessage(text("Formato inválido. Escribe: x y z  (opcional: x y z yaw pitch)",
                    ChatFormatting.RED));
            openPortal(player, portalName);
            return;
        }
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            Float yaw = parts.length == 5 ? Float.parseFloat(parts[3]) : null;
            Float pitch = parts.length == 5 ? Float.parseFloat(parts[4]) : null;
            PortalManager.setTargetCoords(portalName, x, y, z, yaw, pitch);
            player.sendSystemMessage(text(String.format(Locale.US,
                    "Portal '%s': destino fijado en (%.1f, %.1f, %.1f).", portalName, x, y, z),
                    ChatFormatting.GREEN));
        } catch (NumberFormatException e) {
            player.sendSystemMessage(text("Formato inválido. Escribe: x y z  (opcional: x y z yaw pitch)",
                    ChatFormatting.RED));
        }
        openPortal(player, portalName);
    }

    /** Normaliza nombres escritos en el yunque: minúsculas, espacios → '_'. */
    private static String sanitizeName(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s]+", "_")
                .replaceAll("[^a-z0-9_\\-]", "");
    }

    // -------------------------------------------------------------------------
    // Input de texto via menú de yunque (server-side, compatible vanilla)
    // -------------------------------------------------------------------------

    private static void openTextInput(ServerPlayer player, String title, String initial,
                                      BiConsumer<ServerPlayer, String> onConfirm) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TextInputMenu(id, inv, initial, onConfirm),
                Component.literal(title)));
    }

    /**
     * Yunque como campo de texto: el OP escribe en el campo de renombrar y
     * confirma clickeando el ítem resultado (slot 2). ESC cancela.
     */
    private static class TextInputMenu extends AnvilMenu {

        private final BiConsumer<ServerPlayer, String> onConfirm;
        private String text;

        TextInputMenu(int containerId, Inventory playerInventory, String initial,
                      BiConsumer<ServerPlayer, String> onConfirm) {
            super(containerId, playerInventory, ContainerLevelAccess.NULL);
            this.onConfirm = onConfirm;
            this.text = initial;
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME,
                    Component.literal(initial).withStyle(style -> style.withItalic(false)));
            inputSlots.setItem(0, paper);
        }

        @Override
        public boolean setItemName(String name) {
            this.text = name;
            return super.setItemName(name);
        }

        @Override
        public void createResult() {
            // Resultado siempre presente para poder confirmar (sin costo de XP real:
            // el click se intercepta server-side y nunca llega al onTake vanilla).
            ItemStack result = new ItemStack(Items.PAPER);
            String shown = text == null || text.isBlank() ? "(confirmar)" : text;
            result.set(DataComponents.CUSTOM_NAME,
                    Component.literal(shown).withStyle(style -> style.applyFormat(ChatFormatting.GREEN).withItalic(false)));
            resultSlots.setItem(0, result);
            broadcastChanges();
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (slotId == 2 && player instanceof ServerPlayer sp) { // slot resultado = confirmar
                sp.closeContainer();
                onConfirm.accept(sp, text == null ? "" : text.trim());
                return;
            }
            // Cualquier otro click no mueve ítems
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        protected void clearContainer(Player player, Container container) {
            // No devolver el papel del input al cerrar el menú
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Menú de cofre bloqueado: los ítems no se pueden tomar, solo clickear
    // -------------------------------------------------------------------------

    private abstract static class LockedChestMenu extends ChestMenu {

        LockedChestMenu(int containerId, Inventory playerInventory, SimpleContainer container) {
            super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        }

        protected abstract void onClick(int slot, ClickType type, ServerPlayer clicker);

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            // No llamar a super: los ítems del menú nunca se mueven.
            if (player instanceof ServerPlayer sp && slotId >= 0 && slotId < MENU_SIZE
                    && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
                onClick(slotId, clickType, sp);
            }
            sendAllDataToRemote(); // resincronizar la predicción del cliente
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers de ítems
    // -------------------------------------------------------------------------

    private static Component text(String msg, ChatFormatting color) {
        return Component.literal(msg).withStyle(style -> style.applyFormat(color).withItalic(false));
    }

    private static ItemStack named(ItemStack stack, Component name, List<Component> lore) {
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }
}
