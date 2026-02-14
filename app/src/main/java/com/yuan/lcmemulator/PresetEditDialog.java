package com.yuan.lcmemulator;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.jaredrummler.android.colorpicker.ColorPanelView;
import com.jaredrummler.android.colorpicker.ColorPickerDialog;

public class PresetEditDialog extends DialogFragment {

    private final ColorPreset preset;
    private final Listener listener;
    private int[] picked = new int[3];
    private int editingIndex = -1;
    private ColorPanelView blockPanel;
    private ColorPanelView blockPositive;
    private ColorPanelView blockNegative;
    public PresetEditDialog(ColorPreset preset, Listener listener) {
        this.preset = preset;
        this.listener = listener;
        picked[0] = preset.panelColor;
        picked[1] = preset.positiveColor;
        picked[2] = preset.negativeColor;
    }

    public void proxyColorPickDiagReturn(int diagId, int color) {
        picked[editingIndex] = color;
        if (editingIndex == 0) blockPanel.setColor(color);
        else if (editingIndex == 1) blockPositive.setColor(color);
        else blockNegative.setColor(color);
    }

    @NonNull
    @Override
    public AlertDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireActivity())
                .inflate(R.layout.dialog_edit_preset, null);

        EditText etName = view.findViewById(R.id.editPresetName);
        etName.setText(preset.name);

        blockPanel = view.findViewById(R.id.panelMain);
        blockPositive = view.findViewById(R.id.panelPositive);
        blockNegative = view.findViewById(R.id.panelNegative);

        blockPanel.setColor(picked[0]);
        blockPositive.setColor(picked[1]);
        blockNegative.setColor(picked[2]);


        // 点击颜色块开始编辑
        View.OnClickListener blockClick = v -> {
            if (v == blockPanel) editingIndex = 0;
            else if (v == blockPositive) editingIndex = 1;
            else editingIndex = 2;

            ColorPickerDialog.newBuilder().setColor(picked[editingIndex]).show(getActivity());
        };

        blockPanel.setOnClickListener(blockClick);
        blockPositive.setOnClickListener(blockClick);
        blockNegative.setOnClickListener(blockClick);


        AlertDialog.Builder b = new AlertDialog.Builder(requireActivity());
        b.setTitle("Edit Preset");
        b.setView(view);
        b.setPositiveButton("OK", (d, w) -> listener.onPresetEdited(
                etName.getText().toString(),
                picked[0], picked[1], picked[2]));
        b.setNegativeButton("Cancel", null);

        return b.create();
    }

    public interface Listener {
        void onPresetEdited(String newName, int panelColor, int positiveColor, int negativeColor);
    }
}
