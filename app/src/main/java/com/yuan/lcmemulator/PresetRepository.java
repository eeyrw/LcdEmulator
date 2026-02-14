package com.yuan.lcmemulator;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PresetRepository {

    private static final String KEY_CUSTOM = "custom_presets_json";

    private final Context appContext;

    private final Map<String, ColorPreset> builtinMap = new LinkedHashMap<>();
    private final Map<String, ColorPreset> customMap = new LinkedHashMap<>();

    public PresetRepository(Context context) {
        this.appContext = context.getApplicationContext();
        registerBuiltins();
        load();
    }

    // ========================
    // 内置主题
    // ========================

    private void registerBuiltins() {
        // 统一用 registerBuiltin 注册
        registerBuiltin(new ColorPreset("default", "\"Default\"",
                0xFF000000,
                0xFFC8FF00,
                0xFF505050, true));
        registerBuiltin(new ColorPreset("classic_green", "Classic Green",
                0xFF1B3D1B,
                0xFF7CFC00,
                0xFF0A1F0A, true));

        registerBuiltin(new ColorPreset("retro_lcd", "Retro LCD",
                0xFF3A3A1F,
                0xFFB5E61D,
                0xFF1C1C0E, true));

        registerBuiltin(new ColorPreset("ice_blue", "Ice Blue",
                0xFF0A1F2E,
                0xFF00E5FF,
                0xFF051017, true));

        registerBuiltin(new ColorPreset("deep_ocean", "Deep Ocean",
                0xFF001F2F,
                0xFF00BFFF,
                0xFF000F18, true));

        registerBuiltin(new ColorPreset("amber", "Amber",
                0xFF2B1A00,
                0xFFFFB000,
                0xFF140C00, true));

        registerBuiltin(new ColorPreset("monochrome_light", "Monochrome Light",
                0xFFF2F2F2,
                0xFF000000,
                0xFFDADADA, true));

        registerBuiltin(new ColorPreset("terminal", "Terminal",
                0xFF000000,
                0xFFFFFFFF,
                0xFF101010, true));

        registerBuiltin(new ColorPreset("neon_purple", "Neon Purple",
                0xFF1A0033,
                0xFFCC00FF,
                0xFF0D001A, true));

        registerBuiltin(new ColorPreset("warning_red", "Warning Red",
                0xFF2B0000,
                0xFFFF3B3B,
                0xFF140000, true));

        registerBuiltin(new ColorPreset("industrial_cyan", "Industrial Cyan",
                0xFF002626,
                0xFF00FFFF,
                0xFF001414, true));

        registerBuiltin(new ColorPreset("soft_green", "Soft Green",
                0xFF203820,
                0xFF66FF66,
                0xFF101C10, true));

        registerBuiltin(new ColorPreset("warm_white", "Warm White",
                0xFFFFF8E7,
                0xFF222222,
                0xFFE6E0D0, true));

        registerBuiltin(new ColorPreset("military_green", "Military Green",
                0xFF0F2A0F,
                0xFF39FF14,
                0xFF071707, true));

        registerBuiltin(new ColorPreset("ibm_terminal", "IBM Terminal",
                0xFF000000,
                0xFF33FF33,
                0xFF001100, true));

        registerBuiltin(new ColorPreset("crt_orange", "CRT Orange",
                0xFF1A0A00,
                0xFFFF6A00,
                0xFF0D0500, true));

        registerBuiltin(new ColorPreset("industrial_gray", "Industrial Gray",
                0xFF2A2A2A,
                0xFFCCCCCC,
                0xFF1A1A1A, true));

        registerBuiltin(new ColorPreset("medical_monitor", "Medical Monitor",
                0xFF001A1A,
                0xFF00FFCC,
                0xFF000F0F, true));

        registerBuiltin(new ColorPreset("sci_fi_violet", "Sci-Fi Violet",
                0xFF0D001A,
                0xFF7A00FF,
                0xFF05000D, true));

        registerBuiltin(new ColorPreset("midnight", "Midnight",
                0xFF000814,
                0xFF90E0EF,
                0xFF00040A, true));

        registerBuiltin(new ColorPreset("dark_gold", "Dark Gold",
                0xFF1A1400,
                0xFFFFC300,
                0xFF0D0A00, true));

        registerBuiltin(new ColorPreset("instrument_blue", "Instrument Blue",
                0xFF001F33,
                0xFF4FC3F7,
                0xFF00101A, true));

        registerBuiltin(new ColorPreset("cyber_red", "Cyber Red",
                0xFF0D0000,
                0xFFFF1744,
                0xFF050000, true));

        registerBuiltin(new ColorPreset("lavender_gray", "Lavender Gray",
                0xFF2E2A35,
                0xFFD1C4E9,
                0xFF1A1620, true));

        registerBuiltin(new ColorPreset("e_ink", "E-Ink",
                0xFFECECEC,
                0xFF111111,
                0xFFDADADA, true));

        registerBuiltin(new ColorPreset("navy_console", "Navy Console",
                0xFF001233,
                0xFF00B4D8,
                0xFF000A1A, true));

        registerBuiltin(new ColorPreset("hud_cyan", "HUD Cyan",
                0xFF001414,
                0xFF00FFFF,
                0xFF000A0A, true));

        registerBuiltin(new ColorPreset("dos_blue", "DOS Blue",
                0xFF000080,
                0xFFFFFFFF,
                0xFF000040, true));
    }

    private void registerBuiltin(ColorPreset preset) {
        builtinMap.put(preset.id, preset);
    }

    // ========================
    // 查询
    // ========================

    public List<ColorPreset> getBuiltin() {
        return new ArrayList<>(builtinMap.values());
    }

    public List<ColorPreset> getCustom() {
        return new ArrayList<>(customMap.values());
    }

    public List<ColorPreset> getAll() {
        List<ColorPreset> list = new ArrayList<>();
        list.addAll(getBuiltin());
        list.addAll(getCustom());
        return list;
    }

    public ColorPreset getById(String id) {
        if (builtinMap.containsKey(id))
            return builtinMap.get(id);

        if (customMap.containsKey(id))
            return customMap.get(id);

        return builtinMap.get("default");
    }

    // ========================
    // 添加
    // ========================

    public void addCustom(ColorPreset preset) {
        customMap.put(preset.id, preset);
        save();
    }

    // ========================
    // 复制
    // ========================

    public ColorPreset copyPreset(ColorPreset source, String newName) {

        String newId = "custom_" + System.currentTimeMillis();

        ColorPreset copy = new ColorPreset(
                newId,
                newName,
                source.panelColor,
                source.positiveColor,
                source.negativeColor,
                false
        );

        addCustom(copy);
        return copy;
    }

    // ========================
    // 更新
    // ========================

    public void updatePreset(String id,
                             String newName,
                             int panel,
                             int pos,
                             int neg) {

        if (!customMap.containsKey(id)) return;

        customMap.put(id, new ColorPreset(
                id, newName, panel, pos, neg, false));

        save();
    }

    // ========================
    // 删除
    // ========================

    public void removeCustom(String id) {
        customMap.remove(id);
        save();
    }

    // ========================
    // 持久化
    // ========================

    private void load() {

        SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(appContext);

        String json = sp.getString(KEY_CUSTOM, null);
        if (json == null) return;

        try {
            JSONArray arr = new JSONArray(json);

            customMap.clear();

            for (int i = 0; i < arr.length(); i++) {

                JSONObject o = arr.getJSONObject(i);

                ColorPreset p = new ColorPreset(
                        o.getString("id"),
                        o.getString("name"),
                        o.getInt("panel"),
                        o.getInt("pos"),
                        o.getInt("neg"),
                        false
                );

                customMap.put(p.id, p);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save() {

        try {
            JSONArray arr = new JSONArray();

            for (ColorPreset p : customMap.values()) {

                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("panel", p.panelColor);
                o.put("pos", p.positiveColor);
                o.put("neg", p.negativeColor);

                arr.put(o);
            }

            SharedPreferences sp =
                    PreferenceManager.getDefaultSharedPreferences(appContext);

            sp.edit().putString(KEY_CUSTOM, arr.toString()).apply();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}