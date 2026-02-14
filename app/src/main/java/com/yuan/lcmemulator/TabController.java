package com.yuan.lcmemulator;

import android.graphics.Color;
import android.widget.TextView;

import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

public class TabController {
    private final ViewPager2 viewPager;
    private final List<TextView> tabs = new ArrayList<>();

    private int activeBgColor = Color.parseColor("#444444");
    private int inactiveBgColor = Color.parseColor("#222222");
    private int activeTextColor = Color.WHITE;
    private int inactiveTextColor = Color.parseColor("#AAAAAA");

    public TabController(ViewPager2 viewPager) {
        this.viewPager = viewPager;
    }

    public TabController addTab(TextView tab) {
        tabs.add(tab);
        return this;
    }

    public TabController setStyle(int activeBg,
                                  int inactiveBg,
                                  int activeText,
                                  int inactiveText) {
        this.activeBgColor = activeBg;
        this.inactiveBgColor = inactiveBg;
        this.activeTextColor = activeText;
        this.inactiveTextColor = inactiveText;
        return this;
    }

    public void attach() {
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            tabs.get(i).setOnClickListener(v -> scrollToTab(index, true));
        }

        viewPager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        updateTabStyle(position);
                    }
                });

        updateTabStyle(viewPager.getCurrentItem());
    }

    public void updateTabStyle(int selectedIndex) {
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            if (i == selectedIndex) {
                tab.setBackgroundColor(activeBgColor);
                tab.setTextColor(activeTextColor);
            } else {
                tab.setBackgroundColor(inactiveBgColor);
                tab.setTextColor(inactiveTextColor);
            }
        }
    }

    /**
     * 滑动到指定Tab
     *
     * @param index  目标Tab索引
     * @param smooth 是否平滑滚动
     */
    public void scrollToTab(int index, boolean smooth) {
        if (index < 0 || index >= tabs.size()) return;
        viewPager.setCurrentItem(index, smooth);
        updateTabStyle(index); // 同步Tab样式
    }
}
