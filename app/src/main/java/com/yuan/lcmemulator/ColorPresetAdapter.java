package com.yuan.lcmemulator;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

public class ColorPresetAdapter
        extends ListAdapter<ColorPreset, ColorPresetAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<ColorPreset> DIFF =
            new DiffUtil.ItemCallback<ColorPreset>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ColorPreset oldItem,
                        @NonNull ColorPreset newItem) {
                    return oldItem.id.equals(newItem.id);
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ColorPreset oldItem,
                        @NonNull ColorPreset newItem) {
                    return oldItem.equals(newItem);
                }
            };
    private final ActionListener listener;

    public ColorPresetAdapter(ActionListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {

        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_color_preset, parent, false);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder, int position) {

        ColorPreset preset = getItem(position);

        holder.name.setText(preset.name);

        holder.block1.setBackgroundColor(preset.panelColor);
        holder.block2.setBackgroundColor(preset.positiveColor);
        holder.block3.setBackgroundColor(preset.negativeColor);

        holder.itemView.setOnClickListener(v ->
                listener.onSelect(preset));

        holder.btnMore.setOnClickListener(v ->
                showMenu(holder.btnMore, preset));
    }

    private void showMenu(View anchor, ColorPreset p) {

        PopupMenu menu =
                new PopupMenu(anchor.getContext(), anchor);

        menu.inflate(R.menu.menu_preset);

        if (p.isBuiltin) {
            menu.getMenu().findItem(R.id.action_edit)
                    .setVisible(false);
            menu.getMenu().findItem(R.id.action_delete)
                    .setVisible(false);
        }

        menu.setOnMenuItemClickListener(item -> {

            int id = item.getItemId();

            if (id == R.id.action_edit)
                listener.onEdit(p);

            else if (id == R.id.action_copy)
                listener.onCopy(p);

            else if (id == R.id.action_delete)
                listener.onDelete(p);

            return true;
        });

        menu.show();
    }

    public interface ActionListener {
        void onSelect(ColorPreset p);

        void onEdit(ColorPreset p);

        void onCopy(ColorPreset p);

        void onDelete(ColorPreset p);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        TextView name;
        ImageView btnMore;
        View block1, block2, block3;

        ViewHolder(View itemView) {
            super(itemView);

            name = itemView.findViewById(R.id.presetName);
            btnMore = itemView.findViewById(R.id.btnMore);

            block1 = itemView.findViewById(R.id.block1);
            block2 = itemView.findViewById(R.id.block2);
            block3 = itemView.findViewById(R.id.block3);
        }
    }
}
