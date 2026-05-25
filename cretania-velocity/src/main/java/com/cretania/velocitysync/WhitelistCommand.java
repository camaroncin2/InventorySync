package com.cretania.velocitysync;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comandos: /cwhitelist (alias /cwl)
 *   /cwl add &lt;name&gt;
 *   /cwl remove &lt;name&gt;
 *   /cwl list
 *   /cwl on
 *   /cwl off
 *   /cwl reload
 */
public class WhitelistCommand implements SimpleCommand {

    private static final String PERMISSION = "cretania.whitelist";

    private final WhitelistManager whitelist;

    public WhitelistCommand(WhitelistManager whitelist) {
        this.whitelist = whitelist;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        String[] args = inv.arguments();

        if (args.length == 0) {
            usage(src);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add" -> {
                if (args.length < 2) { src.sendMessage(err("Uso: /cwl add <name>")); return; }
                String name = args[1];
                if (whitelist.add(name)) {
                    src.sendMessage(ok("Agregado a la whitelist: " + name));
                } else {
                    src.sendMessage(warn(name + " ya estaba en la whitelist."));
                }
            }
            case "remove", "rm" -> {
                if (args.length < 2) { src.sendMessage(err("Uso: /cwl remove <name>")); return; }
                String name = args[1];
                if (whitelist.remove(name)) {
                    src.sendMessage(ok("Quitado de la whitelist: " + name));
                } else {
                    src.sendMessage(warn(name + " no estaba en la whitelist."));
                }
            }
            case "list" -> {
                Set<String> all = whitelist.all();
                if (all.isEmpty()) {
                    src.sendMessage(info("Whitelist vacía (estado: " + (whitelist.isEnabled() ? "ON" : "OFF") + ")"));
                } else {
                    src.sendMessage(info("Whitelist (" + all.size() + " nombres, "
                            + (whitelist.isEnabled() ? "ON" : "OFF") + "):"));
                    src.sendMessage(Component.text(String.join(", ", all), NamedTextColor.WHITE));
                }
            }
            case "on", "enable" -> {
                whitelist.setEnabled(true);
                src.sendMessage(ok("Whitelist activada."));
            }
            case "off", "disable" -> {
                whitelist.setEnabled(false);
                src.sendMessage(ok("Whitelist desactivada."));
            }
            case "reload" -> {
                whitelist.load();
                src.sendMessage(ok("Whitelist recargada desde disco."));
            }
            default -> usage(src);
        }
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] args = inv.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return Arrays.asList("add", "remove", "list", "on", "off", "reload").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("rm"))) {
            String prefix = args[1].toLowerCase();
            return whitelist.all().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        CommandSource src = inv.source();
        // Consola siempre puede.
        if (!(src instanceof com.velocitypowered.api.proxy.Player)) return true;
        return src.hasPermission(PERMISSION);
    }

    private void usage(CommandSource src) {
        src.sendMessage(info("Comandos disponibles:"));
        src.sendMessage(Component.text("  /cwl add <name>     - agregar a whitelist", NamedTextColor.GRAY));
        src.sendMessage(Component.text("  /cwl remove <name>  - quitar de whitelist", NamedTextColor.GRAY));
        src.sendMessage(Component.text("  /cwl list           - listar nombres", NamedTextColor.GRAY));
        src.sendMessage(Component.text("  /cwl on | off       - activar / desactivar", NamedTextColor.GRAY));
        src.sendMessage(Component.text("  /cwl reload         - recargar desde disco", NamedTextColor.GRAY));
    }

    private Component ok(String s)   { return Component.text("[Cretania] ", NamedTextColor.GREEN).append(Component.text(s, NamedTextColor.WHITE)); }
    private Component warn(String s) { return Component.text("[Cretania] ", NamedTextColor.YELLOW).append(Component.text(s, NamedTextColor.WHITE)); }
    private Component err(String s)  { return Component.text("[Cretania] ", NamedTextColor.RED).append(Component.text(s, NamedTextColor.WHITE)); }
    private Component info(String s) { return Component.text("[Cretania] ", NamedTextColor.AQUA).append(Component.text(s, NamedTextColor.WHITE)); }
}
