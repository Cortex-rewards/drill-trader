package com.example.drilltrader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrillTraderClient implements ClientModInitializer {

    // ===== AYARLAR (sunucudaki gercek lore formatina gore duzenle) =====
    static final String ITEM_NAME_FILTER = "amethyst";
    static final int NEXT_PAGE_SLOT = 53;
    static final int TICK_DELAY = 12;
    static final int MAX_PAGES = 40;
    static final Pattern PRICE = Pattern.compile("(?i)price[^\\d]*([\\d.,]+)\\s*([kmb])?");
    static final Pattern TIME_LINE = Pattern.compile("(?i)(time|left|remaining|duration|süre|kalan)");
    static final Pattern TIME_PART = Pattern.compile("(?i)(\\d+)\\s*([dhms])\\b");
    // ====================================================================

    enum State { IDLE, OPEN_AH, SCANNING, RETURN_OPEN, RETURN_NAV, BUY, CONFIRM }

    State state = State.IDLE;
    int wait = 0, page = 0, bestPage = -1, bestSlot = -1, navDone = 0;
    double bestScore = Double.MAX_VALUE;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("drilltrader")
                .then(ClientCommandManager.literal("start").executes(c -> { start(); return 1; }))
                .then(ClientCommandManager.literal("stop").executes(c -> { state = State.IDLE; msg("Durduruldu"); return 1; }))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void start() {
        page = 0; bestPage = -1; bestSlot = -1; bestScore = Double.MAX_VALUE; navDone = 0;
        state = State.OPEN_AH; wait = 0;
        msg("Basladi");
    }

    void tick(MinecraftClient mc) {
        if (state == State.IDLE || mc.player == null) return;
        if (wait-- > 0) return;
        wait = TICK_DELAY;

        switch (state) {
            case OPEN_AH, RETURN_OPEN -> {
                mc.player.networkHandler.sendChatCommand("ah drill");
                state = (state == State.OPEN_AH) ? State.SCANNING : State.RETURN_NAV;
                wait = 30;
            }
            case SCANNING -> {
                GenericContainerScreenHandler h = handler(mc);
                if (h == null) return;
                scanPage(h);
                if (page >= MAX_PAGES || !hasNext(h)) {
                    if (bestSlot < 0) { msg("Kazma bulunamadi"); state = State.IDLE; return; }
                    msg("En iyi: sayfa " + (bestPage + 1) + " | " + String.format("%.3f", bestScore) + " para/saat");
                    mc.player.closeHandledScreen();
                    state = State.RETURN_OPEN; wait = 20; navDone = 0;
                } else {
                    click(mc, h, NEXT_PAGE_SLOT);
                    page++;
                }
            }
            case RETURN_NAV -> {
                GenericContainerScreenHandler h = handler(mc);
                if (h == null) return;
                if (navDone < bestPage) { click(mc, h, NEXT_PAGE_SLOT); navDone++; }
                else state = State.BUY;
            }
            case BUY -> {
                GenericContainerScreenHandler h = handler(mc);
                if (h == null) return;
                click(mc, h, bestSlot);
                state = State.CONFIRM;
            }
            case CONFIRM -> {
                GenericContainerScreenHandler h = handler(mc);
                if (h == null) return;
                for (int i = 0; i < h.getRows() * 9; i++) {
                    ItemStack s = h.getSlot(i).getStack();
                    if (!s.isEmpty() && s.getName().getString().toLowerCase().contains("confirm")) {
                        click(mc, h, i);
                        msg("Satin alindi!");
                        state = State.IDLE;
                        return;
                    }
                }
            }
            default -> {}
        }
    }

    void scanPage(GenericContainerScreenHandler h) {
        int size = h.getRows() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            if (!s.getName().getString().toLowerCase().contains(ITEM_NAME_FILTER)) continue;

            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null) continue;

            double price = -1, hours = -1;
            for (Text line : lore.lines()) {
                String t = line.getString();
                Matcher pm = PRICE.matcher(t);
                if (pm.find()) price = parsePrice(pm.group(1), pm.group(2));
                if (TIME_LINE.matcher(t).find()) {
                    Matcher tm = TIME_PART.matcher(t);
                    double sum = 0; boolean found = false;
                    while (tm.find()) { sum += parseHours(Integer.parseInt(tm.group(1)), tm.group(2)); found = true; }
                    if (found) hours = sum; // "3h 20m" -> 3.33 saat
                }
            }
            if (price <= 0 || hours <= 0) continue;

            double score = price / hours;
            if (score < bestScore) { bestScore = score; bestPage = page; bestSlot = i; }
        }
    }

    static double parsePrice(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        if (suffix == null) return v;
        return switch (suffix.toLowerCase()) { case "k" -> v * 1e3; case "m" -> v * 1e6; case "b" -> v * 1e9; default -> v; };
    }

    static double parseHours(int n, String unit) {
        return switch (unit.toLowerCase()) { case "d" -> n * 24.0; case "h" -> n; case "m" -> n / 60.0; case "s" -> n / 3600.0; default -> n; };
    }

    boolean hasNext(GenericContainerScreenHandler h) {
        ItemStack s = h.getSlot(NEXT_PAGE_SLOT).getStack();
        return !s.isEmpty() && s.getName().getString().toLowerCase().contains("next");
    }

    GenericContainerScreenHandler handler(MinecraftClient mc) {
        return (mc.currentScreen instanceof GenericContainerScreen gs) ? gs.getScreenHandler() : null;
    }

    void click(MinecraftClient mc, GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[DrillTrader] " + s), false);
    }
}
