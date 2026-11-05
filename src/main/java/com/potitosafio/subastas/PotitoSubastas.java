package com.potitosafio.subastas;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class PotitoSubastas extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final String[] KEYS = {"jade", "azul", "gris"};
    private static final int[] VALUES = {10, 100, 1000};
    private static final String[] LABELS = {"Jades de mercader", "Deditas azules", "Deditas grises"};
    private final Map<String, ItemStack> templates = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private File stateFile;
    private UUID seller;
    private ItemStack prize;
    private UUID leader;
    private String leaderName;
    private long highest;
    private List<ItemStack> escrow = new ArrayList<>();
    private int remaining;
    private BukkitTask countdown;

    @Override public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "estado.yml");
        loadStateAndRecover();
        Objects.requireNonNull(getCommand("subasta")).setExecutor(this);
        Objects.requireNonNull(getCommand("subasta")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PotitoSubastas activo. Registra tus monedas con /subasta registrar jade|azul|gris");
    }

    @Override public void onDisable() {
        if (countdown != null) countdown.cancel();
        if (prize != null) {
            if (leader != null) queue(leader, escrow);
            if (seller != null) queue(seller, List.of(prize));
            clearRound();
        }
        saveState();
    }

    private void loadStateAndRecover() {
        if (!stateFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        for (String key : KEYS) {
            ItemStack sample = y.getItemStack("templates." + key);
            if (sample != null) { sample.setAmount(1); templates.put(key, sample); }
        }
        if (y.isConfigurationSection("pending")) {
            for (String key : Objects.requireNonNull(y.getConfigurationSection("pending")).getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    List<ItemStack> list = itemList(y.getList("pending." + key));
                    if (!list.isEmpty()) pending.put(id, list);
                } catch (IllegalArgumentException ignored) { getLogger().warning("UUID invalido en estado.yml: " + key); }
            }
        }
        // A server restart ends the round safely: all deposits and the prize become claimable.
        ItemStack oldPrize = y.getItemStack("round.prize");
        UUID oldSeller = parseUuid(y.getString("round.seller"));
        UUID oldLeader = parseUuid(y.getString("round.leader"));
        if (oldPrize != null && oldSeller != null) queue(oldSeller, List.of(oldPrize));
        if (oldLeader != null) queue(oldLeader, itemList(y.getList("round.escrow")));
        saveState();
    }

    private UUID parseUuid(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ex) { return null; }
    }

    private List<ItemStack> itemList(List<?> raw) {
        List<ItemStack> out = new ArrayList<>();
        if (raw != null) for (Object obj : raw) if (obj instanceof ItemStack stack && !stack.getType().isAir()) out.add(stack.clone());
        return out;
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        for (var entry : templates.entrySet()) y.set("templates." + entry.getKey(), entry.getValue());
        for (var entry : pending.entrySet()) y.set("pending." + entry.getKey(), entry.getValue());
        if (prize != null) {
            y.set("round.prize", prize);
            y.set("round.seller", seller == null ? null : seller.toString());
            y.set("round.leader", leader == null ? null : leader.toString());
            y.set("round.escrow", escrow);
        }
        try { y.save(stateFile); } catch (IOException e) { getLogger().severe("NO SE PUDO GUARDAR estado.yml: " + e.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> deliverPending(event.getPlayer()), 20L);
    }

    private void queue(UUID id, Collection<ItemStack> stacks) {
        if (id == null || stacks == null) return;
        for (ItemStack item : stacks) if (item != null && !item.getType().isAir())
            pending.computeIfAbsent(id, k -> new ArrayList<>()).add(item.clone());
    }

    private void deliverPending(Player p) {
        List<ItemStack> due = pending.remove(p.getUniqueId());
        if (due == null || due.isEmpty()) return;
        for (ItemStack item : due) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item.clone());
            for (ItemStack overflow : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), overflow);
        }
        tell(p, "&aRecibiste objetos pendientes (si no habia espacio, quedaron en el suelo).");
        saveState();
    }

    // Arclight no expone necesariamente Adventure a los plugins.
    // Usamos el comando vanilla /tellraw: puede mostrar fuentes custom y traducciones
    // del cliente sin cargar clases net.kyori.adventure.
    private String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private String rgbName(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "black"; case '1' -> "dark_blue"; case '2' -> "dark_green";
            case '3' -> "dark_aqua"; case '4' -> "dark_red"; case '5' -> "dark_purple";
            case '6' -> "gold"; case '7' -> "gray"; case '8' -> "dark_gray";
            case '9' -> "blue"; case 'a' -> "green"; case 'b' -> "aqua";
            case 'c' -> "red"; case 'd' -> "light_purple"; case 'e' -> "yellow";
            case 'f' -> "white"; default -> null;
        };
    }

    private void appendText(StringBuilder parts, String text, String color, boolean bold, boolean italic) {
        if (text.isEmpty()) return;
        if (parts.length() > 0) parts.append(',');
        parts.append("{\"text\":").append(jsonEscape(text))
            .append(",\"color\":").append(jsonEscape(color))
            .append(",\"bold\":").append(bold)
            .append(",\"italic\":").append(italic).append('}');
    }

    private String segments(String message) {
        StringBuilder parts = new StringBuilder();
        StringBuilder pendingText = new StringBuilder();
        String color = "white";
        boolean bold = false, italic = false;
        for (int i = 0; i < message.length();) {
            if (message.charAt(i) == '&' && i + 1 < message.length()) {
                char code = Character.toLowerCase(message.charAt(i + 1));
                String nextColor = rgbName(code);
                if (nextColor != null || "klmnor".indexOf(code) >= 0) {
                    appendText(parts, pendingText.toString(), color, bold, italic);
                    pendingText.setLength(0);
                    if (nextColor != null) { color = nextColor; bold = false; italic = false; }
                    else if (code == 'l') bold = true;
                    else if (code == 'o') italic = true;
                    else if (code == 'r') { color = "white"; bold = false; italic = false; }
                    i += 2;
                    continue;
                }
            }
            String glyph = null;
            String symbol = null;
            if (message.startsWith("{jade}", i)) { glyph = "\uE101"; symbol = "{jade}"; }
            else if (message.startsWith("{azul}", i)) { glyph = "\uE102"; symbol = "{azul}"; }
            else if (message.startsWith("{gris}", i)) { glyph = "\uE103"; symbol = "{gris}"; }
            if (glyph != null) {
                appendText(parts, pendingText.toString(), color, bold, italic);
                pendingText.setLength(0);
                if (parts.length() > 0) parts.append(',');
                parts.append("{\"text\":").append(jsonEscape(glyph))
                     .append(",\"font\":\"potitosubastas:monedas\",\"color\":\"white\"}");
                i += symbol.length();
                continue;
            }
            pendingText.append(message.charAt(i++));
        }
        appendText(parts, pendingText.toString(), color, bold, italic);
        return parts.toString();
    }

    private String messageJson(String raw) {
        return "{\"text\":\"\",\"extra\":[" + segments(raw) + "]}";
    }

    private void sendJson(CommandSender sender, String json, String fallback) {
        if (sender instanceof Player p) {
            // Ejecutar desde consola evita permisos de /tellraw para jugadores.
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:tellraw " + p.getName() + " " + json);
            } catch (Exception ex) {
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', fallback));
                getLogger().warning("Fallo tellraw: " + ex.getMessage());
            }
        } else sender.sendMessage(ChatColor.translateAlternateColorCodes('&', fallback));
    }

    private void tell(CommandSender s, String msg) {
        String raw = "&6[PotitoSubastas] &r" + msg;
        sendJson(s, messageJson(raw), raw.replace("{jade}", "[Jade]")
            .replace("{azul}", "[Azul]").replace("{gris}", "[Gris]"));
    }

    private void broadcast(String msg) {
        String raw = "&6[PotitoSubastas] &r" + msg;
        String json = messageJson(raw);
        String fallback = raw.replace("{jade}", "[Jade]")
            .replace("{azul}", "[Azul]").replace("{gris}", "[Gris]");
        for (Player player : Bukkit.getOnlinePlayers()) sendJson(player, json, fallback);
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', fallback));
    }

    private String translatedPrizeJson(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            String display = stack.getItemMeta().getDisplayName();
            return messageJson(display.replace('§', '&'));
        }
        var id = stack.getType().getKey();
        return "{\"translate\":" + jsonEscape("item." + id.getNamespace() + "." + id.getKey()) + "}";
    }

    private void showPrize(CommandSender sender, String prefix, ItemStack item, String suffix) {
        String json = "{\"text\":\"\",\"extra\":["
            + segments(prefix + item.getAmount() + "x &b") + ","
            + translatedPrizeJson(item) + "," + messageJson(suffix) + "]}";
        sendJson(sender, json, prefix + item.getAmount() + "x " + pretty(item) + suffix);
    }

    private void announcePrize(String prefix, ItemStack item, String suffix) {
        String json = "{\"text\":\"\",\"extra\":["
            + segments(prefix + item.getAmount() + "x &b") + ","
            + translatedPrizeJson(item) + "," + messageJson(suffix) + "]}";
        for (Player p : Bukkit.getOnlinePlayers()) sendJson(p, json, prefix + pretty(item) + suffix);
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + pretty(item) + suffix));
    }
    private boolean admin(CommandSender s) { if (s.hasPermission("potitosubastas.admin")) return true; tell(s, "&cNo tienes permiso."); return false; }

    @Override public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ayuda")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("registrar")) {
            if (!admin(sender)) return true;
            if (!(sender instanceof Player p)) { tell(sender, "&cNecesitas sostener una moneda en el juego."); return true; }
            if (args.length != 2 || !Arrays.asList(KEYS).contains(args[1].toLowerCase(Locale.ROOT))) { tell(sender, "&eUso: /subasta registrar jade|azul|gris"); return true; }
            ItemStack held = p.getInventory().getItemInMainHand();
            if (held.getType().isAir()) { tell(sender, "&cSostén una moneda en la mano principal."); return true; }
            String key = args[1].toLowerCase(Locale.ROOT);
            ItemStack template = held.clone(); template.setAmount(1);
            templates.put(key, template);
            saveState();
            tell(sender, "&aMoneda &e" + key + " &aregistrada. Se compararan tipo y metadatos.");
            return true;
        }
        if (sub.equals("iniciar")) {
            if (!admin(sender)) return true;
            if (!(sender instanceof Player p)) { tell(sender, "&cInicia la subasta desde el juego, con el premio en la mano."); return true; }
            if (prize != null) { tell(sender, "&cYa existe una subasta activa."); return true; }
            if (templates.size() != 3) { tell(sender, "&cPrimero registra jade, azul y gris con /subasta registrar."); return true; }
            ItemStack held = p.getInventory().getItemInMainHand();
            if (held.getType().isAir()) { tell(sender, "&cSostén el premio en tu mano principal."); return true; }
            // Deposit the prize immediately to prevent taking it twice.
            prize = held.clone(); seller = p.getUniqueId();
            p.getInventory().setItemInMainHand(null);
            remaining = getConfig().getInt("segundos", 10);
            highest = 0; leader = null; leaderName = null; escrow.clear();
            saveState();
            announcePrize("&6[PotitoSubastas] &r&d✦ &e¡SUBASTA INICIADA! &fPremio: &b", prize, "&f. Puja con &e/subasta pujar <puntos>&f.");
            startTicker();
            return true;
        }
        if (sub.equals("pujar")) {
            if (!(sender instanceof Player p)) { tell(sender, "&cSolo pueden pujar jugadores."); return true; }
            if (prize == null) { tell(sender, "&cNo hay ninguna subasta activa."); return true; }
            if (args.length < 2) { tell(sender, "&eUso: /subasta pujar <puntos>"); return true; }
            long amount;
            try { amount = Long.parseLong(args[1]); } catch (NumberFormatException e) { tell(sender, "&cCantidad no válida."); return true; }
            if (amount < 10 || amount % 10 != 0 || amount > 100_000_000L) { tell(sender, "&cLa oferta debe ser multiplo de 10 (hasta 100,000,000)."); return true; }
            if (amount <= highest) { tell(sender, "&cDebes superar la puja actual: &e" + highest + " puntos."); return true; }
            if (p.getUniqueId().equals(leader)) { tell(sender, "&cYa lideras la subasta. Espera otra oferta."); return true; }
            List<ItemStack> payment = choosePayment(p.getInventory(), amount);
            if (payment == null) { tell(sender, "&cNo cuentas con saldo suficiente en monedas para pagar esa cifra exacta."); return true; }
            // Everything runs on the main server thread. Take the new deposit first, then return the previous one.
            if (!takePayment(p.getInventory(), payment)) { tell(sender, "&cTu inventario cambio. Intenta nuevamente."); return true; }
            UUID oldLeader = leader; List<ItemStack> oldEscrow = new ArrayList<>(escrow);
            leader = p.getUniqueId(); leaderName = p.getName(); highest = amount; escrow = payment;
            remaining = Math.max(1, getConfig().getInt("segundos", 10));
            if (oldLeader != null) {
                queue(oldLeader, oldEscrow);
                Player previous = Bukkit.getPlayer(oldLeader);
                if (previous != null) deliverPending(previous);
            }
            saveState();
            broadcast("&b" + leaderName + " &fpujo &e" + amount + " puntos &7(" + describe(payment) + "&7). &f¡" + remaining + " segundos!");
            return true;
        }
        if (sub.equals("saldo")) {
            if (!(sender instanceof Player p)) { tell(sender, "&cSolo disponible dentro del juego."); return true; }
            int[] counts = counts(p.getInventory());
            long total = 0;
            for (int i = 0; i < 3; i++) total += (long) counts[i] * VALUES[i];
            tell(sender, "&aSaldo de tu inventario: &e" + total + " puntos &7(" + formatCounts(counts) + "&7).");
            return true;
        }
        if (sub.equals("actual")) {
            if (prize == null) tell(sender, "&eNo hay subasta activa.");
            else showPrize(sender, "&6[PotitoSubastas] &r&ePremio: &b", prize, "&f | Mejor puja: &e" + highest + "&f puntos" + (leaderName != null ? " de &b" + leaderName : "") + "&f | Tiempo: &e" + remaining + "s");
            return true;
        }
        if (sub.equals("cancelar")) {
            if (!admin(sender)) return true;
            if (prize == null) { tell(sender, "&cNo hay subasta activa."); return true; }
            if (leader != null) queue(leader, escrow);
            queue(seller, List.of(prize));
            clearRound(); saveState(); deliverOnlinePending();
            broadcast("&cLa subasta fue cancelada. Las monedas y el premio se devolvieron.");
            return true;
        }
        tell(sender, "&cComando desconocido. Usa /subasta ayuda."); return true;
    }

    private void help(CommandSender sender) {
        tell(sender, "&e/subasta pujar <puntos> &7- Pujar con monedas reales del inventario");
        tell(sender, "&e/subasta saldo &7- Ver el saldo disponible");
        tell(sender, "&e/subasta actual &7- Consultar la subasta");
        if (sender.hasPermission("potitosubastas.admin")) {
            tell(sender, "&e/subasta registrar jade|azul|gris &7- Registrar moneda sostenida");
            tell(sender, "&e/subasta iniciar &7- Depositar premio de tu mano e iniciar");
            tell(sender, "&e/subasta cancelar &7- Cancelar y devolver depositos");
        }
    }

    private void startTicker() {
        if (countdown != null) countdown.cancel();
        countdown = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (prize == null) return;
            if (leader == null) return; // First valid bid starts the 10s clock.
            remaining--;
            if (remaining <= 0) { complete(); return; }
            if (remaining <= 5) broadcast("&e⏳ &fQuedan &6" + remaining + " &fsegundos para superar la puja.");
        }, 20L, 20L);
    }

    private void complete() {
        if (leader == null || prize == null) return;
        UUID winner = leader;
        String name = leaderName;
        long bid = highest;
        String detail = describe(escrow);
        ItemStack reward = prize.clone();
        queue(winner, List.of(reward));
        // Deposited coins go to the seller; if you prefer coins to be destroyed, change this behavior.
        if (seller != null) queue(seller, escrow);
        clearRound(); saveState(); deliverOnlinePending();
        broadcast("&a✦ ¡SUBASTA FINALIZADA! &b" + name + " &fganó pujando &e" + bid + " puntos&f: " + detail + "&f.");
    }

    private void clearRound() {
        if (countdown != null) { countdown.cancel(); countdown = null; }
        seller = null; prize = null; leader = null; leaderName = null; highest = 0;
        escrow = new ArrayList<>(); remaining = 0;
    }

    private void deliverOnlinePending() {
        for (UUID id : new ArrayList<>(pending.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) deliverPending(p);
        }
    }

    private int[] counts(PlayerInventory inventory) {
        int[] totals = new int[3];
        // Storage contents excludes armor and offhand: currency must be in the player's normal inventory/hotbar.
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            for (int i = 0; i < KEYS.length; i++) {
                ItemStack template = templates.get(KEYS[i]);
                if (template != null && stack.isSimilar(template)) { totals[i] += stack.getAmount(); break; }
            }
        }
        return totals;
    }

    private List<ItemStack> choosePayment(PlayerInventory inventory, long target) {
        int[] available = counts(inventory);
        if ((long)available[0] * 10 + (long)available[1] * 100 + (long)available[2] * 1000 < target) return null;
        // Bounded exact-change selection. Prefer higher denominations but allow fallback when needed.
        int[] take = new int[3];
        for (int grey = (int)Math.min(available[2], target / 1000); grey >= 0; grey--) {
            long rest = target - grey * 1000L;
            int blue = (int)Math.min(available[1], rest / 100);
            long last = rest - blue * 100L;
            if (last % 10 == 0 && last / 10 <= available[0]) {
                take[2] = grey; take[1] = blue; take[0] = (int)(last / 10);
                List<ItemStack> payment = new ArrayList<>();
                for (int i = 0; i < 3; i++) if (take[i] > 0) {
                    ItemStack sample = templates.get(KEYS[i]);
                    int left = take[i];
                    while (left > 0) {
                        int n = Math.min(left, Math.max(1, sample.getMaxStackSize()));
                        ItemStack stack = sample.clone(); stack.setAmount(n); payment.add(stack); left -= n;
                    }
                }
                return payment;
            }
        }
        return null;
    }

    private boolean takePayment(PlayerInventory inv, List<ItemStack> payment) {
        int[] needs = new int[3];
        for (ItemStack item : payment) for (int i = 0; i < 3; i++) {
            if (item.isSimilar(templates.get(KEYS[i]))) { needs[i] += item.getAmount(); break; }
        }
        int[] available = counts(inv);
        for (int i = 0; i < 3; i++) if (available[i] < needs[i]) return false;
        ItemStack[] storage = inv.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null) continue;
            for (int i = 0; i < 3; i++) {
                if (needs[i] > 0 && item.isSimilar(templates.get(KEYS[i]))) {
                    int removed = Math.min(needs[i], item.getAmount());
                    needs[i] -= removed;
                    if (removed == item.getAmount()) storage[slot] = null;
                    else { ItemStack copy = item.clone(); copy.setAmount(copy.getAmount() - removed); storage[slot] = copy; }
                    break;
                }
            }
        }
        inv.setStorageContents(storage);
        return true;
    }

    private String describe(List<ItemStack> stacks) {
        int[] totals = new int[3];
        for (ItemStack item : stacks) for (int i = 0; i < 3; i++) {
            if (item.isSimilar(templates.get(KEYS[i]))) { totals[i] += item.getAmount(); break; }
        }
        return formatCounts(totals);
    }
    private String formatCounts(int[] amounts) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (amounts[i] > 0) labels.add("&f" + amounts[i] + " " + "{" + KEYS[i] + "}" + " " + LABELS[i]);
        return labels.isEmpty() ? "&7sin monedas" : String.join("&7, ", labels);
    }
    private String pretty(ItemStack item) {
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
            ? item.getItemMeta().getDisplayName() : item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return item.getAmount() + "x " + name;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("pujar", "saldo", "actual", "ayuda"));
            if (sender.hasPermission("potitosubastas.admin")) opts.addAll(List.of("registrar", "iniciar", "cancelar"));
            return opts.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("registrar") && sender.hasPermission("potitosubastas.admin"))
            return Arrays.stream(KEYS).filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        return Collections.emptyList();
    }
}
