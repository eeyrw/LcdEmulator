package com.yuan.lcmemulator;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.Arrays;
import java.util.List;

public class OsdController {

    private final Activity activity;
    private final View overlay;
    private final PresetSelectListener selectListener;
    private ViewPager2 osdPager;
    private ViewPager2 colorPager;
    private RecyclerView recyclerBuiltin;
    private RecyclerView recyclerCustom;
    private PresetRepository presetRepository;
    private ColorPresetAdapter builtinAdapter;
    private ColorPresetAdapter customAdapter;
    private PresetEditDialog presetEditDialog;
    private final ColorPresetAdapter.ActionListener customActionListener =
            new ColorPresetAdapter.ActionListener() {
                @Override
                public void onSelect(ColorPreset p) {
                    if (selectListener != null) selectListener.onPresetSelected(p);
                }

                @Override
                public void onEdit(ColorPreset p) {
                    // 传入当前 Preset 和一个回调
                    presetEditDialog = new PresetEditDialog(p, (newName, panel, pos, neg) -> {
                        // 1️⃣ 更新 Repository
                        presetRepository.updatePreset(p.id, newName, panel, pos, neg);

                        // 2️⃣ 刷新 Custom Adapter
                        if (customAdapter != null) {
                            customAdapter.submitList(presetRepository.getCustom());
                        }

                        if (selectListener != null) selectListener.onPresetSelected(p);
                    });

                    // 3️⃣ 显示 DialogFragment
                    // 这里 activity 必须是 AppCompatActivity
                    presetEditDialog.show(((AppCompatActivity) activity).getSupportFragmentManager(), "editPreset");
                }

                @Override
                public void onCopy(ColorPreset p) {
                    presetRepository.copyPreset(p, p.name + " Copy");
                    refreshCustomList();
                }

                @Override
                public void onDelete(ColorPreset p) {
                    presetRepository.removeCustom(p.id);
                    refreshCustomList();
                }
            };
    private TabController colorPageTabController;
    private final ColorPresetAdapter.ActionListener builtinActionListener =
            new ColorPresetAdapter.ActionListener() {
                @Override
                public void onSelect(ColorPreset p) {
                    if (selectListener != null) selectListener.onPresetSelected(p);
                }

                @Override
                public void onEdit(ColorPreset p) {
                }

                @Override
                public void onCopy(ColorPreset p) {
                    presetRepository.copyPreset(p, p.name + " Copy");
                    refreshCustomList();
                    colorPageTabController.scrollToTab(1, true);
                }

                @Override
                public void onDelete(ColorPreset p) {
                }
            };

    public OsdController(Activity activity, View overlay, PresetRepository presetRepository,
                         PresetSelectListener listener) {
        this.activity = activity;
        this.overlay = overlay;
        this.presetRepository = presetRepository;
        this.selectListener = listener;

        osdPager = overlay.findViewById(R.id.osdPager);
        initPager();
    }

    public void proxyColorPickDialogReturn(int diagId, int color) {
        presetEditDialog.proxyColorPickDiagReturn(diagId, color);
    }

    // =============================
    // 初始化主 Pager
    // =============================
    private void initPager() {

        LayoutInflater inflater = LayoutInflater.from(activity);

        View settingsPage =
                inflater.inflate(R.layout.page_settings, osdPager, false);

        View colorPage =
                inflater.inflate(R.layout.page_color, osdPager, false);

        List<View> pages = Arrays.asList(settingsPage, colorPage);

        osdPager.setAdapter(new SimpleViewPagerAdapter(pages));
        osdPager.setUserInputEnabled(false);

        initSettingsPage(settingsPage);
        initColorPage(colorPage);
    }

    // =============================
    // Settings 页面
    // =============================
    private void initSettingsPage(View page) {

        Button btnOpenColor = page.findViewById(R.id.btnOpenColorPage);
        Button btnMoreSettings = page.findViewById(R.id.btnMoreSettings);
        Button btnClose = page.findViewById(R.id.btnCloseOsd);
        Button btnAbout = page.findViewById(R.id.btnAbout);

        btnOpenColor.setOnClickListener(v ->
                osdPager.setCurrentItem(1, true));

        btnMoreSettings.setOnClickListener(view -> {
            Intent intent = new Intent(activity, SettingsActivity.class);
            activity.startActivity(intent);
        });

        btnClose.setOnClickListener(v -> hide());

        btnAbout.setOnClickListener(v -> {
            Intent intent2 = new Intent(activity, AboutPageActivity.class);
            activity.startActivity(intent2);
        });
    }

    // =============================
    // Color 页面（二级 Pager）
    // =============================
    private void initColorPage(View root) {
        colorPager = root.findViewById(R.id.colorPresentPager);

        Button btnClose = root.findViewById(R.id.btnClose);
        Button btnBack = root.findViewById(R.id.btnBackToSettings);

        btnBack.setOnClickListener(v ->
                osdPager.setCurrentItem(0, true));

        btnClose.setOnClickListener(v -> hide());

        TextView tabPreset = root.findViewById(R.id.tabPreset);
        TextView tabCustom = root.findViewById(R.id.tabCustom);

        colorPageTabController = new TabController(colorPager);
        colorPageTabController.addTab(tabPreset)
                .addTab(tabCustom)
                .attach();

        LayoutInflater inflater = LayoutInflater.from(activity);

        View presentsPage = inflater.inflate(R.layout.page_presents, colorPager, false);
        View customPage = inflater.inflate(R.layout.page_custom, colorPager, false);

        List<View> pages = Arrays.asList(presentsPage, customPage);

        colorPager.setAdapter(new SimpleViewPagerAdapter(pages));
        colorPager.setUserInputEnabled(false);

        recyclerBuiltin = presentsPage.findViewById(R.id.recyclerPreset);
        recyclerCustom = customPage.findViewById(R.id.recyclerCustom);

        recyclerBuiltin.setLayoutManager(new LinearLayoutManager(activity));
        recyclerCustom.setLayoutManager(new LinearLayoutManager(activity));

        // ===========================
        // TODO 完整实现 Adapter
        // ===========================

        builtinAdapter = new ColorPresetAdapter(builtinActionListener);
        customAdapter = new ColorPresetAdapter(customActionListener);

        recyclerBuiltin.setAdapter(builtinAdapter);
        recyclerCustom.setAdapter(customAdapter);

        // 初始化数据
        builtinAdapter.submitList(presetRepository.getBuiltin());
        customAdapter.submitList(presetRepository.getCustom());
    }

    public void refreshCustomList() {
        if (customAdapter != null)
            customAdapter.submitList(presetRepository.getCustom());
    }

    public void refreshAll() {
        if (builtinAdapter != null)
            builtinAdapter.submitList(presetRepository.getBuiltin());
        refreshCustomList();
    }

    // =============================
    // OSD 显示隐藏
    // =============================
    public void show() {
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200);
    }

    public void hide() {

        ObjectAnimator animator = ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f);
        animator.setDuration(200);

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overlay.setVisibility(View.GONE);
                overlay.setAlpha(1f);
            }
        });

        animator.start();
    }

    public interface PresetSelectListener {
        void onPresetSelected(ColorPreset preset);
    }

    // =============================
    // 简单 ViewPager Adapter
    // =============================
    private static class SimpleViewPagerAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<View> pages;

        SimpleViewPagerAdapter(List<View> pages) {
            this.pages = pages;
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(pages.get(viewType)) {
            };
        }

        @Override
        public void onBindViewHolder(
                RecyclerView.ViewHolder holder, int position) {
        }
    }
}
