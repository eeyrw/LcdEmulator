package com.yuan.lcmemulator;

import java.util.Objects;

public class ColorPreset {

    public final String id;
    public final String name;
    public final int panelColor;
    public final int positiveColor;
    public final int negativeColor;
    public final boolean isBuiltin;

    public ColorPreset(String id,
                       String name,
                       int panelColor,
                       int positiveColor,
                       int negativeColor,
                       boolean isBuiltin) {
        this.id = id;
        this.name = name;
        this.panelColor = panelColor;
        this.positiveColor = positiveColor;
        this.negativeColor = negativeColor;
        this.isBuiltin = isBuiltin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColorPreset)) return false;
        ColorPreset that = (ColorPreset) o;
        return panelColor == that.panelColor &&
                positiveColor == that.positiveColor &&
                negativeColor == that.negativeColor &&
                Objects.equals(id, that.id) &&
                Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, panelColor, positiveColor, negativeColor);
    }
}
