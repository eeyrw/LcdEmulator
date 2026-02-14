package com.yuan.lcmemulator;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class ThemeManager {

    private static final String KEY = "selected_preset";

    private final Context appContext;
    private final PresetRepository repository;
    private final List<Listener> listeners = new ArrayList<>();
    private ColorPreset current;

    public ThemeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.repository = new PresetRepository(appContext);
        init();
    }

    // =========================
    // 初始化
    // =========================

    private void init() {
        String id = getSavedPresetId();
        current = repository.getById(id);
    }

    // =========================
    // 当前主题
    // =========================

    public ColorPreset getCurrentPreset() {
        return current;
    }

    public void selectPreset(String id) {
        savePresetId(id);
        current = repository.getById(id);
        notifyListeners();
    }

    public PresetRepository getRepository() {
        return repository;
    }

    // =========================
    // 持久化
    // =========================

    private void savePresetId(String id) {
        SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(appContext);

        sp.edit().putString(KEY, id).apply();
    }

    private String getSavedPresetId() {
        SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(appContext);

        return sp.getString(KEY, "default");
    }

    // =========================
    // 监听
    // =========================

    public void register(Listener l) {
        listeners.add(l);
        if (current != null) {
            l.onThemeChanged(current);
        }
    }

    public void unregister(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onThemeChanged(current);
        }
    }

    public interface Listener {
        void onThemeChanged(ColorPreset preset);
    }
}
