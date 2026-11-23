package app.revanced.extension.gamehub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * BhSensors — device readings shared by the upscaler test log, the performance history and heat
 * control: GPU load, power, charging, and CPU / GPU / battery temperature. Same sources as the
 * detailed HUD (BhDetailedHud): kgsl sysfs, thermal zones, BatteryManager.
 */
final class BhSensors {

    private BhSensors() {}

    /** CPU temperature in °C (first CPU thermal zone found), or 0. */
    static int cpuTemp() {
        return readZoneTemp(new String[]{"cpu-cluster0", "cpu0-thermal", "cpu-thermal", "cpucluster", "cpu"}, null);
    }

    /** GPU temperature in °C (Adreno kgsl, else a GPU thermal zone), or 0. */
    static int gpuTemp() {
        return readZoneTemp(new String[]{"gpuss-0", "gpuss", "gpu-thermal", "gpu"}, "/sys/class/kgsl/kgsl-3d0/temp");
    }


    static int gpuLoad() {
        String v = line("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (v != null) {
            try {
                String[] p = v.trim().split("\\s+");
                if (p.length >= 2) {
                    long busy = Long.parseLong(p[0]), total = Long.parseLong(p[1]);
                    if (total > 0) return (int) (100L * busy / total);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (String path : new String[]{"/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/misc/mali0/device/utilisation"}) {
            v = line(path);
            if (v != null) {
                try {
                    return Integer.parseInt(v.trim().replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    static float power(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return 0f;
            long current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (current == Long.MIN_VALUE) return 0f;
            float volts = 3.7f;
            String v = line("/sys/class/power_supply/battery/voltage_now");
            if (v != null) {
                try {
                    volts = Float.parseFloat(v.trim()) / 1_000_000f;
                } catch (NumberFormatException ignored) {
                }
            }
            float amps = Math.abs(current) / 1_000_000f;
            if (amps < 0.01f) amps = Math.abs(current) / 1_000f;
            return volts * amps;
        } catch (Throwable t) {
            return 0f;
        }
    }

    static boolean charging(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int s = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Battery temperature from Android's battery broadcast (tenths of °C), else sysfs. */
    static int batteryTemp(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (t > 0) return t / 10;
        } catch (Throwable ignored) {
        }
        String v = line("/sys/class/power_supply/battery/temp");
        try {
            return v == null ? 0 : Integer.parseInt(v.trim()) / 10;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int readZoneTemp(String[] types, String direct) {
        if (direct != null) {
            int t = milli(line(direct));
            if (t > 0) return t;
        }
        for (int z = 0; z < 40; z++) {
            String type = line("/sys/class/thermal/thermal_zone" + z + "/type");
            if (type == null) continue;
            String tl = type.trim().toLowerCase(Locale.US);
            for (String t : types) {
                if (tl.contains(t)) {
                    int c = milli(line("/sys/class/thermal/thermal_zone" + z + "/temp"));
                    if (c > 0) return c;
                }
            }
        }
        return 0;
    }

    private static int milli(String v) {
        if (v == null) return 0;
        try {
            int t = Integer.parseInt(v.trim());
            return t > 1000 ? t / 1000 : t;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String line(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }
}
