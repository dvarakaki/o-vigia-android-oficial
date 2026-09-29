package com.ovigia.app.ui.game;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.ovigia.app.R;
import com.ovigia.app.databinding.ItemCharacterBinding;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.ui.Portraits;

/** Lista de personagens com seleção única. */
final class CharacterAdapter extends ListAdapter<CharacterProfile, CharacterAdapter.Holder> {

    interface OnSelectionChanged {
        void onSelected(int characterId);
    }

    private final OnSelectionChanged listener;
    private int selectedId = -1;

    CharacterAdapter(OnSelectionChanged listener) {
        super(DIFF);
        this.listener = listener;
    }

    int selectedId() {
        return selectedId;
    }

    void setSelectedId(int id) {
        if (id == selectedId) return;
        int previous = positionOf(selectedId);
        selectedId = id;
        if (previous >= 0) notifyItemChanged(previous);
        int current = positionOf(id);
        if (current >= 0) notifyItemChanged(current);
    }

    private int positionOf(int id) {
        for (int i = 0; i < getItemCount(); i++) {
            if (getItem(i).id == id) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCharacterBinding binding = ItemCharacterBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CharacterProfile profile = getItem(position);
        holder.binding.tvName.setText(profile.name);
        holder.binding.getRoot().setActivated(profile.id == selectedId);
        holder.binding.getRoot().setSelected(profile.id == selectedId);
        holder.binding.getRoot().setOnClickListener(v -> {
            setSelectedId(profile.id);
            listener.onSelected(profile.id);
        });
        Glide.with(holder.itemView)
                .load(profile.thumbnailUrl)
                .placeholder(R.drawable.ic_character_placeholder)
                .error(R.drawable.ic_character_placeholder)
                .transform(Portraits.roundedCrop(Portraits.cardRadius(holder.itemView.getResources())))
                .into(holder.binding.image);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemCharacterBinding binding;

        Holder(ItemCharacterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final DiffUtil.ItemCallback<CharacterProfile> DIFF = new DiffUtil.ItemCallback<CharacterProfile>() {
        @Override
        public boolean areItemsTheSame(@NonNull CharacterProfile a, @NonNull CharacterProfile b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull CharacterProfile a, @NonNull CharacterProfile b) {
            return a.id == b.id && String.valueOf(a.name).equals(String.valueOf(b.name));
        }
    };
}
