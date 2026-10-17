package com.donutprofit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutProfitClient implements ClientModInitializer {

    /** Edit config/donutprofit.json to match DonutSMP's real chat messages. Group 1 must be the amount. */
    public static class Config {
        public String serverFilter = "donutsmp"; // only track when server address contains this ("" = any server)
        public boolean hud = true;
        public List<String> gain = new ArrayList<>(List.of(
                "(?i)paid you \\$([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)you (?:have )?received \\$?([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)\\$([\\d,]+(?:\\.\\d+)?[kmbt]?) (?:has been|was) (?:added|deposited)",
                "(?i)sold .* for \\$?([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)\\+\\s?\\$([\\d,]+(?:\\.\\d+)?[kmbt]?)"));
        public List<String> loss = new ArrayList<>(List.of(
                "(?i)you paid \\S+ \\$([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)you (?:have )?paid \\$?([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)\\$([\\d,]+(?:\\.\\d+)?[kmbt]?) (?:has been|was) (?:taken|removed|withdrawn)",
                "(?i)purchased .* for \\$?([\\d,]+(?:\\.\\d+)?[kmbt]?)",
                "(?i)\\-\\s?\\$([\\d,]+(?:\\.\\d+)?[kmbt]?)"));
    }

    record Tx(long timeMs, double amount, String message) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();
    private static final List<Pattern> gainP = new ArrayList<>();
    private static final List<Pattern> lossP = new ArrayList<>();

    private static FabricClientCommandSource src;
    private static boolean running = false;
    private static long startMs;
    private static double total;
    private static final List<Tx> txs = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        loadConfig();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) -> {
            dispatcher.register(ClientCommands.literal("start").executes(c -> { src = c.getSource(); start(); return 1; }));
            dispatcher.register(ClientCommands.literal("stop").executes(c -> { src = c.getSource(); stop(); return 1; }));
        });

        // System/game messages only (plugin messages) so other players can't fake transactions in chat.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay || !running || !onTargetServer()) return;
            handle(msg.getString());
        });

        // Profit readout in the action bar (no custom rendering code, so it survives rendering changes).
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!running || !config.hud || mc.player == null || mc.level == null) return;
            if (mc.level.getGameTime() % 10 != 0) return;
            long el = System.currentTimeMillis() - startMs;
            double perHour = el > 0 ? total / (el / 3_600_000.0) : 0;
            String color = total >= 0 ? "§a" : "§c";
            mc.gui.setOverlayMessage(Component.literal(color + "Profit " + money(total) + " §7| §f" + dur(el)
                    + " §7| §b" + money(perHour) + "/h §7| §f" + txs.size() + " tx"), false);
        });
    }

    private static void start() {
        if (running) { say("§eAlready running. Use /stop first."); return; }
        running = true;
        startMs = System.currentTimeMillis();
        total = 0;
        txs.clear();
        say("§aProfit tracking started.");
        if (!onTargetServer()) say("§7Note: not on a server matching \"" + config.serverFilter + "\" - nothing will be tracked until you join it.");
    }

    private static void stop() {
        if (!running) { say("§cNot running. Use /start first."); return; }
        running = false;
        long el = System.currentTimeMillis() - startMs;
        double hours = el / 3_600_000.0;
        double perHour = hours > 0 ? total / hours : 0;

        List<String> lines = new ArrayList<>();
        lines.add("===== Profit Summary =====");
        lines.add("Duration: " + dur(el));
        lines.add("Total profit: " + money(total));
        lines.add("Hourly average: " + money(perHour) + "/h");
        lines.add("Transactions: " + txs.size());
        lines.add("----- Every transaction -----");
        int i = 1;
        for (Tx t : txs) {
            lines.add(String.format(Locale.US, "#%d [%s] %s%s", i++, dur(t.timeMs() - startMs),
                    t.amount() >= 0 ? "+" : "-", money(Math.abs(t.amount()))));
        }
        for (String l : lines) say(l.startsWith("=") || l.startsWith("-") ? "§6" + l : l.contains("Hourly") ? "§b" + l : "§f" + l);

        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("donutprofit-logs");
            Files.createDirectories(dir);
            Path f = dir.resolve("session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");
            List<String> out = new ArrayList<>(lines);
            out.add("");
            out.add("Raw messages:");
            for (Tx t : txs) out.add("[" + dur(t.timeMs() - startMs) + "] " + t.message());
            Files.write(f, out);
            say("§7Saved to " + f.getFileName());
        } catch (IOException e) {
            say("§cCould not save log: " + e.getMessage());
        }
    }

    private static void handle(String raw) {
        String s = raw.replaceAll("§.", "");
        for (Pattern p : gainP) {
            Matcher m = p.matcher(s);
            if (m.find()) { record(+parse(m.group(1)), s); return; }
        }
        for (Pattern p : lossP) {
            Matcher m = p.matcher(s);
            if (m.find()) { record(-parse(m.group(1)), s); return; }
        }
    }

    private static void record(double amount, String msg) {
        total += amount;
        txs.add(new Tx(System.currentTimeMillis(), amount, msg));
    }

    private static double parse(String str) {
        str = str.replace(",", "").trim();
        double mult = 1;
        char last = Character.toLowerCase(str.charAt(str.length() - 1));
        if (Character.isLetter(last)) {
            switch (last) {
                case 'k' -> mult = 1e3;
                case 'm' -> mult = 1e6;
                case 'b' -> mult = 1e9;
                case 't' -> mult = 1e12;
            }
            str = str.substring(0, str.length() - 1);
        }
        return Double.parseDouble(str) * mult;
    }

    private static boolean onTargetServer() {
        if (config.serverFilter == null || config.serverFilter.isBlank()) return true;
        ServerData sd = Minecraft.getInstance().getCurrentServer();
        return sd != null && sd.ip != null && sd.ip.toLowerCase().contains(config.serverFilter.toLowerCase());
    }

    private static String money(double v) { return String.format(Locale.US, "$%,.2f", v); }

    private static String dur(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static void say(String s) {
        if (src != null) src.sendFeedback(Component.literal(s));
    }

    private static void loadConfig() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("donutprofit.json");
        try {
            if (Files.exists(f)) config = GSON.fromJson(Files.readString(f), Config.class);
            else Files.writeString(f, GSON.toJson(config));
        } catch (Exception e) { e.printStackTrace(); }
        for (String r : config.gain) gainP.add(Pattern.compile(r));
        for (String r : config.loss) lossP.add(Pattern.compile(r));
    }
}
