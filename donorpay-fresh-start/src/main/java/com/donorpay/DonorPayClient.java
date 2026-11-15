package com.donorpay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonorPayClient implements ClientModInitializer {

    // ===== SETTINGS =====
    static final double SMALL_ALERT = 10_000_000d;      // $10M+   -> right-side popup (10 random styles)
    static final double MID_ALERT   = 50_000_000d;      // $50M+   -> bigger popup, extra sounds
    static final double BIG_ALERT   = 100_000_000d;     // $100M+  -> full-screen animation (5 types)
    static final double MEGA_ALERT  = 1_000_000_000d;   // $1B+    -> FAKE PC CRASH, then "JK YOU'RE RICH"
    static final long POPUP_MS = 5500;
    static final String CRASH_TEXT = "YOUR PC CRASHED FROM TOO MUCH MONEY";
    static final String JK_TEXT = "§e§lJK §a§lYOU'RE RICH!!!";

    // "Name paid you $ 10M"  -> group(1) = name, group(2) = amount
    static final Pattern PAID = Pattern.compile("(\\S{1,20}) (?:paid|sent) you[^\\d]{0,6}([\\d,.]+(?:\\s?[KMBTkmbt])?)(?![A-Za-z])");
    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("donorpay");
    static final Random RNG = new Random();

    // ===== STATE =====
    static class Popup { String name; double amount; long start; int style; boolean mid; boolean burst; }
    static class P { float x, y, vx, vy, life, max, size, grav; int rgb; String ch; }
    static class Snd { long at; SoundEvent ev; float pitch, vol; }

    static final List<Popup> popups = new ArrayList<>();
    static final List<P> parts = new ArrayList<>();
    static final List<Snd> sounds = new ArrayList<>();
    static final List<Integer> smallBag = new ArrayList<>();
    static final List<Integer> bigBag = new ArrayList<>();
    static Popup big = null;
    static int bigType = 0;
    static long introMs = 1900, bigMs = 10000;
    static boolean exploded = false;
    static int nextBurst = 0;
    static float flash = 0f;
    static long lastNano = 0;
    static boolean boltInit = false;
    static float[] bx = new float[16], by = new float[16], brx = new float[7], bry = new float[7];

    // ===== TABLES =====
    static final int STYLES = 10;
    static final int T_ROCKET = 0, T_METEOR = 1, T_LIGHT = 2, T_SLOTS = 3, T_RAIN = 4, T_CRASH = 99;
    static final long[] INTRO = {1900, 1700, 1500, 2800, 700};
    static final long CRASH_INTRO = 6600;
    static final String[] TCOL = {"§6", "§c", "§b", "§e", "§a"};
    static final long[] BURST_REL = {600, 1200, 1800, 2400, 3000, 3600, 4200, 4800};
    static final int[] FLAME = {0xFF9800, 0xFFC107, 0xFFEB3B, 0xFFFFFF, 0xFF5722};
    static final int[] CONFETTI = {0xFF3B3B, 0xFFD700, 0x3BA7FF, 0x3BFF7A, 0xFF4DD2, 0xFFFFFF, 0xB05CFF};
    static final int[] GLITCHC = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x00FFFF, 0xFF00FF, 0xFFFF00, 0x000000};
    static final int[][] PALETTES = {
            {0xFFD700, 0xFFA500, 0xFFF2A0}, {0xFF3B3B, 0xFFFFFF, 0xFF9A9A}, {0x3BA7FF, 0xA0E0FF, 0xFFFFFF},
            {0x3BFF7A, 0xB8FFD0, 0xFFFFFF}, {0xFF4DD2, 0xFFB3EE, 0xFFFFFF}
    };
    static final int[] BG = {0xD0101010, 0xD01A0F2E, 0xD0082210, 0xD0201505, 0xF0000000, 0xD0222222, 0xD0300A00, 0xD0000000, 0xD0101018, 0xD0062806};
    static final int[] EDGE = {0xFFFFD700, 0xFFFF4DD2, 0xFF3BFF7A, 0xFFFFAA00, 0xFF00FF41, 0xFFFFFFFF, 0xFFFF5722, 0xFF00E5FF, 0xFFFFFFFF, 0xFF2E7D32};
    static final String[] TITLE = {"§6§lPAYMENT RECEIVED", "§d§lMONEY INCOMING!", "§a§lYOU GOT PAID!", "§6§lCA-CHING!", "", "§f§lCOIN FLIP WIN!",
            "§c§lFIRE PAYMENT!!", "SYSTEM ALERT", "", "§2§l$$$ CASH $$$"};
    static final String RAINBOW = "c6eab9d";

    @Override
    public void onInitializeClient() {
        LOG.info("[donorpay] loaded");
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handle(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> handle(message.getString()));

        // Test command (never sent to the server):  /donortest 1b      /donortest 150m 2      /donortest 20m 5
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String c = cmd.trim();
            if (!c.toLowerCase(Locale.ROOT).startsWith("donortest")) return true;
            String[] a = c.split("\\s+");
            double amt = a.length > 1 ? parse(a[1]) : 10_000_000d;
            int force = -1;
            if (a.length > 2) { try { force = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { } }
            if (amt <= 0) amt = 10_000_000d;
            trigger("TestUser", amt, force);
            return false;
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("donorpay", "hud"),
                (g, delta) -> render(g));
    }

    // ===================== DETECTION =====================
    static void handle(String text) {
        if (text.toLowerCase(Locale.ROOT).contains("paid you")) LOG.info("[donorpay] saw: {}", text);
        Matcher m = PAID.matcher(text);
        if (!m.find()) return;
        double amt = parse(m.group(2));
        if (amt < SMALL_ALERT) return;
        trigger(m.group(1), amt, -1);
    }

    static int pick(List<Integer> bag, int n) {
        if (bag.isEmpty()) { for (int i = 0; i < n; i++) bag.add(i); Collections.shuffle(bag, RNG); }
        return bag.remove(bag.size() - 1);
    }

    static void trigger(String name, double amt, int force) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            long now = System.currentTimeMillis();
            Popup p = new Popup();
            p.name = name; p.amount = amt; p.start = now;
            if (amt >= MEGA_ALERT) {
                startBig(p, T_CRASH, now);
            } else if (amt >= BIG_ALERT) {
                startBig(p, force >= 0 ? force % 5 : pick(bigBag, 5), now);
            } else {
                p.style = force >= 0 ? force % STYLES : pick(smallBag, STYLES);
                p.mid = amt >= MID_ALERT;
                popups.add(p);
                scheduleSmallSounds(now, p);
            }
        });
    }

    static void startBig(Popup p, int type, long now) {
        big = p; bigType = type; exploded = false; nextBurst = 0; flash = 0; boltInit = false;
        parts.clear(); sounds.clear();
        introMs = type == T_CRASH ? CRASH_INTRO : INTRO[type];
        bigMs = introMs + (type == T_CRASH ? 9500 : 8300);
        scheduleBigSounds(now, type);
    }

    static double parse(String s) {
        s = s.trim().replace(",", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return 0;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'K' -> mult = 1e3; case 'M' -> mult = 1e6;
            case 'B' -> mult = 1e9; case 'T' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try { return Double.parseDouble(s) * mult; } catch (NumberFormatException e) { return 0; }
    }

    static String fmt(double v) {
        if (v >= 1e12) return trim(v / 1e12) + "T";
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }
    static String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    // ===================== SOUNDS =====================
    static void snd(long at, SoundEvent ev, float pitch) { snd(at, ev, pitch, 1.0f); }
    static void snd(long at, SoundEvent ev, float pitch, float vol) {
        Snd s = new Snd(); s.at = at; s.ev = ev; s.pitch = pitch; s.vol = vol; sounds.add(s);
    }
    static void run(long at, SoundEvent ev, float p0, float dp, int n, long gap, float vol) {
        for (int i = 0; i < n; i++) snd(at + i * gap, ev, p0 + i * dp, vol);
    }

    static void scheduleSmallSounds(long t, Popup p) {
        SoundEvent orb = SoundEvents.EXPERIENCE_ORB_PICKUP, lvl = SoundEvents.PLAYER_LEVELUP, arrow = SoundEvents.ARROW_HIT_PLAYER;
        SoundEvent twink = SoundEvents.FIREWORK_ROCKET_TWINKLE, blast = SoundEvents.FIREWORK_ROCKET_BLAST, toast = SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
        switch (p.style) {
            case 0 -> { run(t, orb, 0.9f, 0.2f, 3, 90, 1f); snd(t + 300, lvl, 1.2f); }
            case 1 -> { snd(t, arrow, 1.5f); snd(t + 120, twink, 1.0f); snd(t + 260, orb, 1.5f); snd(t + 340, lvl, 1.0f); }
            case 2 -> { snd(t, toast, 1.0f, 0.8f); snd(t + 150, blast, 1.2f); run(t + 300, orb, 1.3f, 0.3f, 2, 100, 1f); }
            case 3 -> { run(t, orb, 1.8f, 0f, 8, 150, 0.5f); snd(t + 1200, arrow, 1.2f); snd(t + 1250, toast, 1.0f, 0.7f); snd(t + 1400, lvl, 1.0f); }
            case 4 -> { run(t, orb, 2.0f, 0f, 10, 70, 0.4f); snd(t + 900, arrow, 1.0f); snd(t + 950, lvl, 0.8f); }
            case 5 -> { run(t, orb, 1.0f, 0.15f, 4, 300, 1f); snd(t + 1400, lvl, 1.1f); snd(t + 1450, twink, 1.0f); }
            case 6 -> { snd(t, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.7f); snd(t + 120, blast, 1.0f); snd(t + 300, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.2f); run(t + 400, orb, 1.0f, 0.2f, 3, 80, 1f); }
            case 7 -> { run(t, arrow, 0.5f, 0f, 6, 70, 0.6f); snd(t + 600, arrow, 1.8f); snd(t + 900, toast, 1.0f, 0.8f); }
            case 8 -> { run(t, twink, 1.2f, 0.1f, 3, 150, 1f); snd(t + 500, lvl, 1.4f); run(t + 600, orb, 1.0f, 0.1f, 4, 90, 1f); }
            default -> { run(t, orb, 0.8f, 0.12f, 8, 80, 1f); snd(t + 800, lvl, 1.1f); }
        }
        if (p.mid) {
            snd(t + 500, SoundEvents.TOTEM_USE, 1.3f, 0.7f);
            snd(t + 650, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f);
            snd(t + 800, lvl, 1.5f);
            run(t + 900, orb, 0.9f, 0.17f, 5, 90, 1f);
        }
    }

    static void scheduleBigSounds(long t, int type) {
        SoundEvent orb = SoundEvents.EXPERIENCE_ORB_PICKUP, lvl = SoundEvents.PLAYER_LEVELUP, arrow = SoundEvents.ARROW_HIT_PLAYER;
        SoundEvent toast = SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, blast = SoundEvents.FIREWORK_ROCKET_BLAST;
        SoundEvent lblast = SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, twink = SoundEvents.FIREWORK_ROCKET_TWINKLE, totem = SoundEvents.TOTEM_USE;
        long R = type == T_CRASH ? CRASH_INTRO : INTRO[type];
        switch (type) {
            case T_ROCKET -> {
                snd(t, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0f); snd(t + 350, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.8f);
                run(t + 150, orb, 0.6f, 0.14f, 8, 210, 0.7f);
            }
            case T_METEOR -> {
                snd(t, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.5f); snd(t + 500, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6f);
                snd(t + 1100, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.6f, 0.6f);
                run(t + 200, orb, 0.5f, 0.1f, 7, 200, 0.6f);
                snd(t + R, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6f);
            }
            case T_LIGHT -> {
                snd(t + 650, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.9f); snd(t + 950, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.0f);
                snd(t + 1250, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.8f); snd(t + 1250, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.9f);
                snd(t + R, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.7f);
            }
            case T_SLOTS -> {
                run(t, orb, 1.9f, 0f, 10, 100, 0.5f);
                snd(t + 1000, arrow, 1.0f); snd(t + 1600, arrow, 1.2f); snd(t + 2200, arrow, 1.5f);
                snd(t + 2300, twink, 1.3f);
            }
            case T_RAIN -> run(t, orb, 0.8f, 0.2f, 6, 100, 1f);
            default -> { // fake crash: buzzing glitch, then ANVIL, then silence until the cut
                run(t, arrow, 0.5f, 0f, 12, 70, 0.6f);
                snd(t + 900, SoundEvents.ANVIL_LAND, 0.6f);
            }
        }
        // ----- the reveal (same for every type) -----
        snd(t + R, lblast, 0.8f); snd(t + R, totem, 1.0f); snd(t + R + 80, blast, 0.7f);
        snd(t + R + 150, toast, 1.0f); snd(t + R + 600, lvl, 1.0f);
        if (type == T_CRASH) {
            snd(t + R + 250, lblast, 1.1f); snd(t + R + 400, totem, 1.2f);
            run(t + R + 450, orb, 0.8f, 0.12f, 12, 70, 1f);
        }
        for (int i = 0; i < BURST_REL.length; i++) {
            snd(t + R + BURST_REL[i] + (type == T_CRASH ? 2000 : 0), (i % 2 == 0) ? blast : lblast, 0.8f + (i % 4) * 0.15f);
            snd(t + R + BURST_REL[i] + (type == T_CRASH ? 2000 : 0) + 120, twink, 1.0f + (i % 3) * 0.1f);
        }
        long cs = t + R + 3300 + (type == T_CRASH ? 2000 : 0);
        run(cs, orb, 0.8f, 0.08f, 12, 110, 0.8f);
        snd(cs + 1400, lvl, 1.3f); snd(cs + 2000, toast, 1.2f); snd(cs + 2600, totem, 1.4f, 0.6f);
    }

    static void playDueSounds(Minecraft mc, long nowMs) {
        for (Iterator<Snd> it = sounds.iterator(); it.hasNext(); ) {
            Snd s = it.next();
            if (s.at <= nowMs) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(s.ev, s.pitch, s.vol));
                it.remove();
            }
        }
    }

    // ===================== PARTICLES =====================
    static void spawn(float x, float y, float vx, float vy, float life, int rgb, float size, float grav) {
        if (parts.size() > 2800) return;
        P p = new P();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.life = life; p.max = life; p.rgb = rgb; p.size = size; p.grav = grav;
        parts.add(p);
    }
    static void spawnChar(String ch, float x, float y, float vx, float vy, float life, int rgb, float grav) {
        if (parts.size() > 2800) return;
        P p = new P();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.life = life; p.max = life; p.rgb = rgb; p.grav = grav; p.ch = ch;
        parts.add(p);
    }

    static void explosion(float cx, float cy, float sc, int count, int[] pal, float grav, boolean ring) {
        for (int i = 0; i < count; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            float sp = (30 + RNG.nextFloat() * 250) * sc;
            spawn(cx, cy, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 1.1f + RNG.nextFloat() * 1.2f,
                    RNG.nextInt(4) == 0 ? 0xFFFFFF : pal[RNG.nextInt(pal.length)], Math.max(2f, (2 + RNG.nextFloat() * 3) * sc), grav * sc);
        }
        if (ring) {
            for (int i = 0; i < 80; i++) {
                double a = i / 80.0 * Math.PI * 2;
                spawn(cx, cy, (float) Math.cos(a) * 240 * sc, (float) Math.sin(a) * 240 * sc, 0.9f, 0xFFFFFF, Math.max(2f, 3 * sc), 0);
            }
        }
    }

    static void firework(float cx, float cy, float sc) {
        int[] pal = PALETTES[RNG.nextInt(PALETTES.length)];
        for (int i = 0; i < 100; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            float sp = (40 + RNG.nextFloat() * 150) * sc;
            spawn(cx, cy, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 1.0f + RNG.nextFloat() * 0.9f,
                    pal[RNG.nextInt(pal.length)], Math.max(2f, (2 + RNG.nextFloat() * 2) * sc), 80 * sc);
        }
    }

    static void tickParticles(GuiGraphicsExtractor g, Minecraft mc, float dt) {
        for (Iterator<P> it = parts.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.vy += p.grav * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            float a = Math.min(1f, p.life / p.max * 1.6f);
            int x = Math.round(p.x), y = Math.round(p.y);
            int argb = (((int) (a * 255)) << 24) | p.rgb;
            if (p.ch != null) {
                if (a > 0.05f) g.text(mc.font, p.ch, x, y, argb, false);
            } else {
                int sz = Math.max(1, Math.round(p.size));
                g.fill(x, y, x + sz, y + sz, argb);
            }
        }
    }

    // ===================== HELPERS =====================
    static float ease(float t) { t = Math.max(0, Math.min(1, t)); return 1 - (float) Math.pow(1 - t, 3); }
    static float easeBack(float t) {
        t = Math.max(0, Math.min(1, t));
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        int ri = Math.max(1, Math.round(r)), ix = Math.round(cx), iy = Math.round(cy);
        for (int dy = -ri; dy <= ri; dy++) {
            int hw = (int) Math.sqrt(Math.max(0, ri * ri - dy * dy));
            g.fill(ix - hw, iy + dy, ix + hw, iy + dy + 1, argb);
        }
    }

    static void line(GuiGraphicsExtractor g, float ax, float ay, float bx2, float by2, int thick, int argb) {
        int steps = (int) Math.max(Math.abs(bx2 - ax), Math.abs(by2 - ay));
        if (steps < 1) steps = 1;
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(ax + (bx2 - ax) * i / steps), y = Math.round(ay + (by2 - ay) * i / steps);
            g.fill(x, y, x + thick, y + thick, argb);
        }
    }

    static String rainbow(String s, long age) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int k = (int) ((i + age / 90) % RAINBOW.length());
            sb.append('§').append(RAINBOW.charAt(k)).append("§l").append(s.charAt(i));
        }
        return sb.toString();
    }

    static void drawScaled(GuiGraphicsExtractor g, Minecraft mc, String text, float cx, float cy, float scale, int alpha) {
        if (alpha < 5 || scale <= 0.01f) return;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        int tw = mc.font.width(text);
        g.text(mc.font, text, -tw / 2, -4, (alpha << 24) | 0xFFFFFF, true);
        g.pose().popMatrix();
    }
    static float fit(Minecraft mc, String text, float desired, int w) {
        int tw = Math.max(1, mc.font.width(text));
        return Math.min(desired, w * 0.92f / tw);
    }

    // ===================== RENDER =====================
    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();
        long nowMs = System.currentTimeMillis();
        long nano = System.nanoTime();
        float dt = lastNano == 0 ? 0.016f : Math.min(0.05f, (nano - lastNano) / 1e9f);
        lastNano = nano;
        float sc = Math.max(0.8f, h / 270f);

        playDueSounds(mc, nowMs);
        renderPopups(g, mc, w, nowMs, sc);

        long bigAge = -1;
        if (big != null) {
            bigAge = nowMs - big.start;
            if (bigAge > bigMs) { big = null; bigAge = -1; }
        }
        if (bigAge >= 0) bigBack(g, mc, w, h, bigAge, sc);
        tickParticles(g, mc, dt);
        if (bigAge >= 0) bigFront(g, mc, w, h, bigAge, sc);

        if (flash > 0) {
            g.fill(0, 0, w, h, (((int) (Math.min(1f, flash) * 210)) << 24) | 0xFFFFFF);
            flash -= dt * 1.6f;
        }
    }

    // ----- $10M+ popups, 10 styles -----
    static void renderPopups(GuiGraphicsExtractor g, Minecraft mc, int w, long now, float sc) {
        int y = 30;
        for (Iterator<Popup> it = popups.iterator(); it.hasNext(); ) {
            Popup p = it.next();
            long age = now - p.start;
            if (age > POPUP_MS) { it.remove(); continue; }
            int bw = p.mid ? 185 : 160, bh = p.mid ? 42 : 36;
            float prog;
            if (age < 400) prog = (p.style == 1 || p.style == 6) ? easeBack(age / 400f) : ease(age / 400f);
            else if (age > POPUP_MS - 350) prog = ease((POPUP_MS - age) / 350f);
            else prog = 1f;
            int x = Math.round(w - 6 - bw * prog);

            float sx = 1f;
            if (p.style == 5 && age < 1500) sx = Math.max(0.06f, (float) Math.abs(Math.cos(age / 1500.0 * Math.PI * 3)));

            int bg = BG[p.style], edge = EDGE[p.style];
            String title = TITLE[p.style];
            if (p.mid && p.style != 4 && p.style != 7) title = "§b§lHUGE PAYMENT!!";
            if (p.mid && (age / 120) % 2 == 0) edge = 0xFF00E5FF;
            if (p.style == 8) edge = 0xFF000000 | CONFETTI[(int) ((age / 90) % CONFETTI.length)];
            if (p.style == 8) title = rainbow("NEON PAYMENT", age);
            String amt = "$" + fmt(p.amount);
            if (p.style == 3 && age < 1200) {
                StringBuilder sb = new StringBuilder();
                for (char c : amt.toCharArray()) sb.append(Character.isDigit(c) ? (char) ('0' + RNG.nextInt(10)) : c);
                amt = sb.toString();
            }
            String line = "§f" + p.name + " §apaid you §2" + amt;

            g.pose().pushMatrix();
            g.pose().translate(x + bw / 2f, y);
            g.pose().scale(sx, 1f);
            int l = -bw / 2;
            g.fill(l, 0, l + bw, bh, bg);
            g.fill(l, 0, l + 3, bh, edge);
            g.fill(l, 0, l + bw, 1, edge);
            g.fill(l, bh - 1, l + bw, bh, edge);
            int ty2 = p.mid ? 24 : 21;

            if (p.style == 4) { // terminal: typed text
                String t1 = "> TRANSFER COMPLETE", t2 = p.name + " -> $" + fmt(p.amount);
                int n = (int) (age / 35);
                String s1 = t1.substring(0, Math.min(t1.length(), n));
                String s2 = n > t1.length() ? t2.substring(0, Math.min(t2.length(), n - t1.length())) : "";
                String cur = ((age / 300) % 2 == 0) ? "_" : "";
                g.text(mc.font, "§a" + s1 + (s2.isEmpty() ? cur : ""), l + 9, 6, 0xFFFFFFFF, true);
                g.text(mc.font, "§f" + s2 + (s2.isEmpty() ? "" : cur), l + 9, ty2, 0xFFFFFFFF, true);
            } else if (p.style == 7 && age < 900) { // glitch: RGB split + jitter
                int j = RNG.nextInt(5) - 2;
                g.text(mc.font, "§l" + title, l + 9 + 2 + j, 6, 0xFFFF0000, false);
                g.text(mc.font, "§l" + title, l + 9 - 2 - j, 6, 0xFF00FFFF, false);
                g.text(mc.font, "§b§l" + title, l + 9, 6, 0xFFFFFFFF, true);
                g.text(mc.font, line, l + 9 + j, ty2, 0xFFFFFFFF, true);
                int ty = RNG.nextInt(bh - 4);
                g.fill(l + 1, ty, l + bw - 1, ty + 2, 0x66FFFFFF);
            } else {
                g.text(mc.font, title, l + 9, 6, 0xFFFFFFFF, true);
                g.text(mc.font, line, l + 9, ty2, 0xFFFFFFFF, true);
            }
            if (p.style == 2 || p.mid) { // shine sweep
                float sh = l + ((age % 1100) / 1100f) * (bw + 30) - 15;
                int a = Math.max(l, (int) sh), b = Math.min(l + bw, (int) sh + 12);
                if (b > a) g.fill(a, 1, b, bh - 1, 0x55FFFFFF);
            }
            g.pose().popMatrix();

            // style-specific particles in screen space
            if (p.style == 6) { // flames rising off the top
                for (int i = 0; i < 2; i++) spawn(x + RNG.nextFloat() * bw, y + 2, (RNG.nextFloat() - 0.5f) * 20 * sc, -(40 + RNG.nextFloat() * 60) * sc,
                        0.5f + RNG.nextFloat() * 0.4f, FLAME[RNG.nextInt(FLAME.length)], Math.max(2f, 3 * sc), 0);
            }
            if (p.style == 9 && age < POPUP_MS - 600) { // $ signs raining out
                spawnChar("$", x + RNG.nextFloat() * bw, y + bh - 2, (RNG.nextFloat() - 0.5f) * 15, 20 + RNG.nextFloat() * 30,
                        1.6f, RNG.nextBoolean() ? 0x3BFF7A : 0xFFD700, 45);
            }
            if (!p.burst && age > 60) { // coin / sparkle burst
                p.burst = true;
                int n = p.mid ? 70 : 30;
                int[] pal = p.mid ? CONFETTI : PALETTES[p.style % PALETTES.length];
                for (int i = 0; i < n; i++) {
                    spawn(x, y + bh / 2f, -(30 + RNG.nextFloat() * 170) * sc, (RNG.nextFloat() - 0.7f) * 140 * sc,
                            0.8f + RNG.nextFloat() * 0.8f, pal[RNG.nextInt(pal.length)], Math.max(2f, (2 + RNG.nextFloat() * 2) * sc), 160 * sc);
                }
            }
            y += bh + 5;
        }
    }

    // ----- $100M+ intro / reveal -----
    static void bigBack(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        boolean crash = bigType == T_CRASH;
        float fade = age > bigMs - 1000 ? Math.max(0f, (bigMs - age) / 1000f) : 1f;
        float inA = Math.min(1f, age / 300f);

        if (age < introMs) {
            if (crash) { crashIntro(g, mc, w, h, age, sc); return; }
            int dim = bigType == T_LIGHT ? 205 : 140;
            g.fill(0, 0, w, h, ((int) (inA * dim) << 24));
            switch (bigType) {
                case T_ROCKET -> introRocket(g, mc, w, h, age, sc);
                case T_METEOR -> introMeteor(g, mc, w, h, age, sc);
                case T_LIGHT -> introLightning(g, mc, w, h, age, sc);
                case T_SLOTS -> introSlots(g, mc, w, h, age, sc);
                default -> drawScaled(g, mc, "§a§lMAKE IT RAIN!!", w / 2f, h / 2f, fit(mc, "MAKE IT RAIN!!", 3f * sc, w) * (0.8f + 0.2f * (float) Math.sin(age / 60.0)), 255);
            }
            return;
        }

        g.fill(0, 0, w, h, ((int) (fade * inA * 140) << 24));
        if (!exploded) {
            exploded = true;
            switch (bigType) {
                case T_ROCKET -> { flash = 1f; explosion(w / 2f, h * 0.30f, sc, 260, PALETTES[0], 90, true); }
                case T_METEOR -> { flash = 1f; explosion(w / 2f, h * 0.55f, sc, 280, FLAME, 110, true);
                    explosion(w / 2f, h * 0.55f, sc, 120, new int[]{0x8D6E63, 0x9E9E9E, 0x424242}, 150, false); }
                case T_LIGHT -> { flash = 1f; explosion(bx[15], by[15], sc, 240, new int[]{0x3BA7FF, 0xA0E0FF, 0xFFFFFF}, 60, true); }
                case T_SLOTS -> { flash = 0.6f;
                    for (int i = 0; i < 170; i++) spawn(w / 2f + (RNG.nextFloat() - 0.5f) * 30 * sc, h * 0.85f, (RNG.nextFloat() - 0.5f) * 140 * sc,
                            -(160 + RNG.nextFloat() * 220) * sc, 2.2f, CONFETTI[1 + RNG.nextInt(2)] , Math.max(2f, 3 * sc), 260 * sc); }
                case T_RAIN -> { flash = 0.5f; explosion(w / 2f, h / 2f, sc, 100, PALETTES[3], 60, false); }
                default -> { flash = 1f; explosion(w / 2f, h / 2f, sc, 300, PALETTES[0], 80, true);
                    for (int i = 0; i < 4; i++) firework(w * 0.15f + RNG.nextFloat() * w * 0.7f, h * (0.15f + RNG.nextFloat() * 0.5f), sc); }
            }
        }
    }

    static void introRocket(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        float p = age / (float) introMs, e = p * p;
        float startY = h + 70 * sc, endY = h * 0.30f;
        float rx = w / 2f + (float) Math.sin(age / 70.0) * 2f * sc;
        float ry = startY + (endY - startY) * e;
        for (int i = 0; i < 5; i++) {
            spawn(rx + (RNG.nextFloat() - 0.5f) * 8 * sc, ry + 30 * sc, (RNG.nextFloat() - 0.5f) * 50 * sc, (70 + RNG.nextFloat() * 130) * sc,
                    0.5f + RNG.nextFloat() * 0.6f, FLAME[RNG.nextInt(FLAME.length)], Math.max(2f, (2 + RNG.nextFloat() * 3) * sc), 0);
        }
        if (RNG.nextBoolean()) spawn(rx + (RNG.nextFloat() - 0.5f) * 10 * sc, ry + 36 * sc, (RNG.nextFloat() - 0.5f) * 30 * sc, (40 + RNG.nextFloat() * 50) * sc, 1.1f, 0x9E9E9E, Math.max(3f, 4 * sc), 0);
        drawRocket(g, rx, ry, 3.2f * sc, age);
        if ((age / 140) % 2 == 0) drawScaled(g, mc, "§c§l!! HUGE PAYMENT INCOMING !!", w / 2f, h * 0.12f, fit(mc, "!! HUGE PAYMENT INCOMING !!", 1.6f * sc, w), 255);
    }

    static void introMeteor(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        float p = age / (float) introMs, e = (float) Math.pow(p, 1.5);
        float sx = w * 0.05f, sy = -30 * sc, ex = w / 2f, ey = h * 0.55f;
        float mx = sx + (ex - sx) * e, my = sy + (ey - sy) * e;
        for (int i = 0; i < 6; i++) {
            spawn(mx + (RNG.nextFloat() - 0.5f) * 10 * sc, my + (RNG.nextFloat() - 0.5f) * 10 * sc, -(30 + RNG.nextFloat() * 50) * sc * (ex - sx) / w,
                    -(30 + RNG.nextFloat() * 50) * sc * 0.5f, 0.6f + RNG.nextFloat() * 0.6f, FLAME[RNG.nextInt(FLAME.length)], Math.max(2f, (2 + RNG.nextFloat() * 4) * sc), 0);
        }
        disc(g, mx, my, 11 * sc, 0xFFFF5722);
        disc(g, mx, my, 8 * sc, 0xFFFFA000);
        disc(g, mx, my, 4 * sc, 0xFFFFFFFF);
        if ((age / 140) % 2 == 0) drawScaled(g, mc, "§c§l!! MONEY METEOR INCOMING !!", w / 2f, h * 0.12f, fit(mc, "!! MONEY METEOR INCOMING !!", 1.6f * sc, w), 255);
    }

    static void introLightning(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        if (!boltInit) {
            boltInit = true;
            float x = w / 2f + (RNG.nextFloat() - 0.5f) * w * 0.25f;
            for (int i = 0; i < 16; i++) { by[i] = i * (h * 0.6f) / 15f; x += (RNG.nextFloat() - 0.5f) * 36 * sc; bx[i] = x; }
            float cx = bx[6], cy = by[6];
            for (int i = 0; i < 7; i++) { brx[i] = cx; bry[i] = cy; cx += 14 * sc + RNG.nextFloat() * 14 * sc; cy += 10 * sc + RNG.nextFloat() * 14 * sc; }
        }
        boolean on = (age >= 650 && age < 780) || (age >= 950 && age < 1060) || age >= 1250;
        if (on) {
            if (age % 400 < 60) flash = Math.max(flash, 0.5f);
            for (int i = 0; i < 15; i++) line(g, bx[i], by[i], bx[i + 1], by[i + 1], Math.max(3, Math.round(5 * sc)), 0x884DA3FF);
            for (int i = 0; i < 15; i++) line(g, bx[i], by[i], bx[i + 1], by[i + 1], Math.max(1, Math.round(2 * sc)), 0xFFFFFFFF);
            for (int i = 0; i < 6; i++) line(g, brx[i], bry[i], brx[i + 1], bry[i + 1], Math.max(1, Math.round(2 * sc)), 0xFFB3E5FF);
        } else if (age < 650) {
            drawScaled(g, mc, "§b§l...", w / 2f, h / 2f, 3f * sc, 255);
        }
    }

    static void introSlots(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        String[] sym = {"7", "$", "#", "*", "B"};
        int rw = Math.round(46 * sc), rh = Math.round(60 * sc), gap = Math.round(8 * sc);
        int total = rw * 3 + gap * 2, x0 = (w - total) / 2, y0 = Math.round(h / 2f - rh / 2f);
        g.fill(x0 - 8, y0 - 8, x0 + total + 8, y0 + rh + 8, 0xFFFFD700);
        for (int i = 0; i < 3; i++) {
            long stop = 1000 + i * 600L;
            boolean stopped = age >= stop;
            int rx = x0 + i * (rw + gap);
            g.fill(rx, y0, rx + rw, y0 + rh, stopped ? 0xFF3A0A0A : 0xFF111111);
            String s = stopped ? "7" : sym[(int) ((age / 55 + i * 2) % sym.length)];
            String col = stopped ? "§e§l" : "§f§l";
            drawScaled(g, mc, col + s, rx + rw / 2f, y0 + rh / 2f, 3.4f * sc, 255);
        }
        if ((age / 160) % 2 == 0 && age > 2300) drawScaled(g, mc, "§6§lJACKPOT!!!", w / 2f, y0 - 22 * sc, 2.4f * sc, 255);
    }

    static void crashIntro(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        if (age < 900) { // glitch freeze
            if ((age / 90) % 2 == 0) g.fill(0, 0, w, h, 0x55000000);
            int n = 6 + (int) (age / 35);
            for (int i = 0; i < n; i++) {
                int x = RNG.nextInt(w), y = RNG.nextInt(h), bw2 = RNG.nextInt(Math.max(10, w / 2)) + 10, bh2 = RNG.nextInt(10) + 2;
                g.fill(x, y, x + bw2, y + bh2, 0xFF000000 | GLITCHC[RNG.nextInt(GLITCHC.length)]);
            }
        } else if (age < 6000) { // fake blue screen
            g.fill(0, 0, w, h, 0xFF0078D7);
            float p = (age - 900) / 5100f;
            int pct = (int) (100 * (p < 0.5f ? p * 1.2f : 0.6f + (p - 0.5f) * 0.8f));
            drawScaled(g, mc, ":(", w / 2f, h * 0.22f, 7f * sc, 255);
            drawScaled(g, mc, "§l" + CRASH_TEXT, w / 2f, h * 0.46f, fit(mc, "§l" + CRASH_TEXT, 1.8f * sc, w), 255);
            drawScaled(g, mc, "Collecting error info: " + Math.min(100, pct) + "% complete", w / 2f, h * 0.58f, fit(mc, "Collecting error info: 100% complete", 1.1f * sc, w), 255);
            drawScaled(g, mc, "Stop code: MONEY_OVERFLOW_EXCEPTION", w / 2f, h * 0.70f, fit(mc, "Stop code: MONEY_OVERFLOW_EXCEPTION", 0.9f * sc, w), 230);
            drawScaled(g, mc, "What failed: wallet.sys", w / 2f, h * 0.76f, fit(mc, "What failed: wallet.sys", 0.9f * sc, w), 230);
        } else { // dead black right before the reveal
            g.fill(0, 0, w, h, 0xFF000000);
        }
    }

    static void bigFront(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long age, float sc) {
        if (age < introMs) return;
        boolean crash = bigType == T_CRASH;
        long rel = age - introMs;
        float fade = age > bigMs - 1000 ? Math.max(0f, (bigMs - age) / 1000f) : 1f;
        int alpha = (int) (fade * 255);
        long shift = crash ? 2000 : 0;

        while (nextBurst < BURST_REL.length && rel >= BURST_REL[nextBurst] + shift) {
            firework(w * 0.1f + RNG.nextFloat() * w * 0.8f, h * (0.12f + RNG.nextFloat() * 0.4f), sc);
            nextBurst++;
        }
        if (age < bigMs - 1500) {
            for (int i = 0; i < 3; i++) {
                spawn(RNG.nextFloat() * w, -5, (RNG.nextFloat() - 0.5f) * 40 * sc, (50 + RNG.nextFloat() * 90) * sc, 4.5f,
                        CONFETTI[RNG.nextInt(CONFETTI.length)], Math.max(2f, 3 * sc), 25 * sc);
            }
            if (bigType == T_RAIN) {
                for (int i = 0; i < 7; i++) spawnChar("$", RNG.nextFloat() * w, -8, (RNG.nextFloat() - 0.5f) * 30, (80 + RNG.nextFloat() * 90) * sc, 4.5f,
                        RNG.nextBoolean() ? 0x3BFF7A : 0xFFD700, 30 * sc);
            }
        }

        if (crash && rel < 2000) { // the "JK" moment
            float s = easeBack(rel / 450f);
            float shake = rel < 500 ? (RNG.nextFloat() - 0.5f) * 6 : 0;
            drawScaled(g, mc, JK_TEXT, w / 2f + shake, h / 2f, fit(mc, "JK YOU'RE RICH!!!", 4.5f * sc, w) * s, 255);
            return;
        }

        float pulse = 0.5f + 0.5f * (float) Math.sin(age / 150.0);
        int barA = (int) (alpha * (0.5f + 0.5f * pulse));
        int gold = (barA << 24) | 0xFFD700;
        int barY1 = Math.round(h / 2f - 58 * sc), barY2 = Math.round(h / 2f + 58 * sc);
        g.fill(0, barY1, w, barY1 + 3, gold);
        g.fill(0, barY2, w, barY2 + 3, gold);

        float s = easeBack((rel - shift) / 650f);
        float bob = (float) Math.sin(age / 200.0) * 2f * sc;
        String tc = crash ? "§6" : TCOL[bigType];
        drawScaled(g, mc, tc + "§l$" + fmt(big.amount) + " RECEIVED!", w / 2f, h / 2f - 32 * sc + bob, 3.2f * s * sc, alpha);
        drawScaled(g, mc, "§e§lSHOUT OUT TO", w / 2f, h / 2f + 2 * sc, 1.6f * s * sc, alpha);
        drawScaled(g, mc, rainbow(big.name, age), w / 2f, h / 2f + 24 * sc - bob, 3.0f * s * sc, alpha);
    }

    static void drawRocket(GuiGraphicsExtractor g, float x, float y, float s, long age) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        int fh = 8 + (int) (Math.abs(Math.sin(age / 40.0)) * 9);
        g.fill(-3, 12, 3, 12 + fh, 0xFFFF5722);
        g.fill(-2, 12, 2, 12 + fh - 3, 0xFFFF9800);
        g.fill(-1, 12, 1, 12 + (fh * 6 / 10), 0xFFFFEB3B);
        g.fill(-9, 4, -4, 13, 0xFFE53935);
        g.fill(-7, 1, -4, 5, 0xFFE53935);
        g.fill(4, 4, 9, 13, 0xFFE53935);
        g.fill(4, 1, 7, 5, 0xFFE53935);
        g.fill(-4, -8, 4, 11, 0xFFEEEEEE);
        g.fill(2, -8, 4, 11, 0xFFBDBDBD);
        g.fill(-4, 3, 4, 5, 0xFFE53935);
        g.fill(-3, -11, 3, -8, 0xFFE53935);
        g.fill(-2, -14, 2, -11, 0xFFE53935);
        g.fill(-1, -17, 1, -14, 0xFFE53935);
        g.fill(-3, -6, 3, 0, 0xFF555555);
        g.fill(-2, -5, 2, -1, 0xFF42A5F5);
        g.fill(-2, -5, 0, -3, 0xFFBBDEFB);
        g.fill(-3, 11, 3, 13, 0xFF616161);
        g.pose().popMatrix();
    }
}
