package x1vemfps.modid.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** X1VEMFPS v2 Android performance HUD, FiveM-inspired. */
public final class X1vemFPSClient implements ClientModInitializer {
    private static final String MOD_ID = "x1vemfps";
    private static final Path CONFIG = Path.of("config", "x1vemfps.json");
    private static final int TEXT = 0xFFE8EDF2;
    private static final int BORDER = 0xC7AEBCCD;
    private static final int DIM = 0xFF9EA8B3;

    private static Settings settings;
    private static KeyMapping menuKey;
    private static long lastSample;
    private static long lastCpuTotal;
    private static long lastCpuIdle;
    private static int cpuPercent = -1;
    private static int gpuPercent = -1;
    private static int gpuTemp = -1;
    private static long cpuMhz = -1;
    private static long gpuMhz = -1;
    private static long lastHudUpdate;

    @Override
    public void onInitializeClient() {
        settings = Settings.load();
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.x1vemfps.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.x1vemfps"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.consumeClick()) {
                mc.setScreen(new SettingsScreen());
            }
            sampleMetrics();
        });
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> render(graphics));
    }

    private static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen instanceof SettingsScreen) return;
        if (settings == null || !settings.enabled) return;

        if (System.currentTimeMillis() - lastHudUpdate > settings.intervalMs) sampleMetrics();

        String[] parts = new String[5];
        int count = 0;
        if (settings.showFps) parts[count++] = "FPS: " + mc.getFps();
        if (settings.showCpu) parts[count++] = "CPU: " + (cpuPercent < 0 ? "--" : cpuPercent + "%") + (settings.showFrequency && cpuMhz > 0 ? " " + cpuMhz + "MHz" : "");
        if (settings.showGpu) parts[count++] = "GPU: " + (gpuPercent < 0 ? "--" : gpuPercent + "%") + (settings.showFrequency && gpuMhz > 0 ? " " + gpuMhz + "MHz" : "");
        if (settings.showGpuTemp) parts[count++] = "GPU Temp: " + (gpuTemp < 0 ? "--" : gpuTemp + "°C");
        if (settings.showPing) parts[count++] = "Ping: " + getPing(mc) + "ms";
        if (count == 0) return;

        int screenW = g.guiWidth();
        int margin = Math.max(8, settings.margin);
        int totalW = screenW - margin * 2;
        int colW = Math.max(70, totalW / count);
        int x = margin;
        int y = Math.max(6, settings.y);
        int height = Math.max(18, settings.fontSize + settings.padding * 2 + 2);
        for (int i = 0; i < count; i++) {
            drawCell(g, x, y, colW + (i == count - 1 ? totalW - colW * count : 0), height, parts[i]);
            x += colW;
        }
    }

    private static void drawCell(GuiGraphics g, int x, int y, int width, int height, String text) {
        int t = Math.max(1, Math.min(4, settings.border));
        int alpha = 0x10; // transparent; no filled background panel
        if (alpha > 0) g.fill(x, y, x + width, y + height, alpha << 24);
        g.fill(x, y, x + width, y + t, BORDER);
        g.fill(x, y + height - t, x + width, y + height, BORDER);
        g.fill(x, y, x + t, y + height, BORDER);
        g.fill(x + width - t, y, x + width, y + height, BORDER);
        Minecraft mc = Minecraft.getInstance();
        int textY = y + Math.max(2, (height - mc.font.lineHeight) / 2);
        g.drawString(mc.font, Component.literal(text), x + settings.padding + 2, textY, TEXT, false);
    }

    private static int getPing(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return 0;
        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    private static void sampleMetrics() {
        long now = System.currentTimeMillis();
        if (now - lastHudUpdate < settings.intervalMs) return;
        lastHudUpdate = now;
        long[] stat = readCpuStat();
        if (stat != null && lastSample != 0) {
            long totalDelta = stat[0] - lastCpuTotal;
            long idleDelta = stat[1] - lastCpuIdle;
            if (totalDelta > 0) cpuPercent = (int) Math.max(0, Math.min(100, Math.round((totalDelta - idleDelta) * 100.0 / totalDelta)));
        }
        if (stat != null) { lastCpuTotal = stat[0]; lastCpuIdle = stat[1]; lastSample = now; }
        gpuPercent = readPercent("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/gpu_busy");
        gpuTemp = readTemp();
        cpuMhz = readMaxCpuFreq();
        gpuMhz = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/gpu_clock_mhz");
    }

    private static long[] readCpuStat() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
            String[] p = line.trim().split("\\s+");
            long user = Long.parseLong(p[1]), nice = Long.parseLong(p[2]), system = Long.parseLong(p[3]);
            long idle = Long.parseLong(p[4]), iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
            long total = user + nice + system + idle + iowait;
            return new long[]{total, idle + iowait};
        } catch (Throwable ignored) { return null; }
    }

    private static long readMaxCpuFreq() {
        long sum = 0; int n = 0;
        for (int i = 0; i < 16; i++) {
            long v = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (v > 0) { sum += v / 1000; n++; }
        }
        return n == 0 ? -1 : Math.round(sum / (double)n);
    }

    private static int readTemp() {
        String[] paths = {
                "/sys/class/kgsl/kgsl-3d0/temp", "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp", "/sys/class/thermal/thermal_zone2/temp"
        };
        for (String p : paths) {
            long v = readLong(p);
            if (v > 1000) v /= 1000;
            if (v >= 0 && v <= 150) return (int)v;
        }
        return -1;
    }

    private static int readPercent(String... paths) {
        for (String p : paths) {
            long v = readLong(p);
            if (v >= 0 && v <= 100) return (int)v;
        }
        return -1;
    }

    private static long readLong(String... paths) {
        for (String p : paths) {
            try { return Long.parseLong(Files.readString(Path.of(p)).trim().split("\\s+")[0]); }
            catch (Throwable ignored) { }
        }
        return -1;
    }

    static final class Settings {
        boolean enabled = true, showFps = true, showCpu = true, showGpu = true, showGpuTemp = true, showPing = true, showFrequency = true;
        int border = 1, fontSize = 16, padding = 6, y = 8, margin = 8;
        long intervalMs = 500;

        static Settings load() {
            Settings s = new Settings();
            try {
                String j = Files.readString(CONFIG);
                s.enabled = bool(j, "enabled", s.enabled); s.showFps = bool(j, "showFps", s.showFps); s.showCpu = bool(j, "showCpu", s.showCpu);
                s.showGpu = bool(j, "showGpu", s.showGpu); s.showGpuTemp = bool(j, "showGpuTemp", s.showGpuTemp); s.showPing = bool(j, "showPing", s.showPing);
                s.showFrequency = bool(j, "showFrequency", s.showFrequency); s.border = integer(j, "border", s.border); s.fontSize = integer(j, "fontSize", s.fontSize);
                s.padding = integer(j, "padding", s.padding); s.y = integer(j, "y", s.y); s.margin = integer(j, "margin", s.margin); s.intervalMs = integer(j, "intervalMs", (int)s.intervalMs);
            } catch (Throwable ignored) { }
            return s;
        }
        void save() {
            try {
                Files.createDirectories(CONFIG.getParent());
                String j = "{\n" +
                        "  \"enabled\": " + enabled + ",\n  \"showFps\": " + showFps + ",\n  \"showCpu\": " + showCpu + ",\n" +
                        "  \"showGpu\": " + showGpu + ",\n  \"showGpuTemp\": " + showGpuTemp + ",\n  \"showPing\": " + showPing + ",\n" +
                        "  \"showFrequency\": " + showFrequency + ",\n  \"border\": " + border + ",\n  \"fontSize\": " + fontSize + ",\n" +
                        "  \"padding\": " + padding + ",\n  \"y\": " + y + ",\n  \"margin\": " + margin + ",\n  \"intervalMs\": " + intervalMs + "\n}\n";
                Files.writeString(CONFIG, j);
            } catch (IOException ignored) { }
        }
        private static boolean bool(String j, String k, boolean d) { String v = value(j,k); return v == null ? d : Boolean.parseBoolean(v); }
        private static int integer(String j, String k, int d) { try { String v=value(j,k); return v==null?d:Integer.parseInt(v); } catch(Exception e){return d;} }
        private static String value(String j,String k){ int p=j.indexOf("\""+k+"\""); if(p<0)return null; int c=j.indexOf(':',p); if(c<0)return null; int e=j.indexOf(',',c); if(e<0)e=j.indexOf('}',c); if(e<0)return null; return j.substring(c+1,e).trim().replace("\"",""); }
    }

    private static final class SettingsScreen extends Screen {
        private int left, top;
        SettingsScreen() { super(Component.literal("X1VEMFPS v2 Settings")); }
        @Override protected void init() {
            left = width / 2 - 150; top = height / 2 - 110;
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("FPS: " + on(settings.showFps)), b -> { settings.showFps=!settings.showFps; b.setMessage(Component.literal("FPS: " + on(settings.showFps))); settings.save(); }).bounds(left, top+25, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("CPU: " + on(settings.showCpu)), b -> { settings.showCpu=!settings.showCpu; b.setMessage(Component.literal("CPU: " + on(settings.showCpu))); settings.save(); }).bounds(left, top+50, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("GPU: " + on(settings.showGpu)), b -> { settings.showGpu=!settings.showGpu; b.setMessage(Component.literal("GPU: " + on(settings.showGpu))); settings.save(); }).bounds(left, top+75, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("GPU Temp: " + on(settings.showGpuTemp)), b -> { settings.showGpuTemp=!settings.showGpuTemp; b.setMessage(Component.literal("GPU Temp: " + on(settings.showGpuTemp))); settings.save(); }).bounds(left, top+100, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Ping: " + on(settings.showPing)), b -> { settings.showPing=!settings.showPing; b.setMessage(Component.literal("Ping: " + on(settings.showPing))); settings.save(); }).bounds(left, top+125, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Border: " + settings.border + "px"), b -> { settings.border = settings.border % 4 + 1; b.setMessage(Component.literal("Border: " + settings.border + "px")); settings.save(); }).bounds(left+155, top+25, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Font: " + settings.fontSize), b -> { settings.fontSize = settings.fontSize >= 22 ? 12 : settings.fontSize + 2; b.setMessage(Component.literal("Font: " + settings.fontSize)); settings.save(); }).bounds(left+155, top+50, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Padding: " + settings.padding), b -> { settings.padding = settings.padding >= 12 ? 2 : settings.padding + 2; b.setMessage(Component.literal("Padding: " + settings.padding)); settings.save(); }).bounds(left+155, top+75, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Interval: " + settings.intervalMs + "ms"), b -> { settings.intervalMs = settings.intervalMs >= 2000 ? 250 : settings.intervalMs + 250; b.setMessage(Component.literal("Interval: " + settings.intervalMs + "ms")); settings.save(); }).bounds(left+155, top+100, 140, 20).build());
            addRenderableWidget(new net.minecraft.client.gui.components.Button.Builder(Component.literal("Frequency: " + on(settings.showFrequency)), b -> { settings.showFrequency=!settings.showFrequency; b.setMessage(Component.literal("Frequency: " + on(settings.showFrequency))); settings.save(); }).bounds(left+155, top+125, 140, 20).build());
        }
        private static String on(boolean b){return b?"ON":"OFF";}
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            renderBackground(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, title, width/2, top, TEXT);
            g.drawCenteredString(font, Component.literal("Press X to open this menu anytime"), width/2, top+158, DIM);
            super.render(g, mouseX, mouseY, delta);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
