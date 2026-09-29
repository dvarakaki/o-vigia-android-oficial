package com.ovigia.app.ui.catalog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ovigia.app.R;
import com.ovigia.app.databinding.ItemRefRowBinding;
import com.ovigia.app.databinding.SheetRefListBinding;
import com.ovigia.app.model.ApiRef;
import com.ovigia.app.util.SearchText;

import java.util.ArrayList;
import java.util.List;

/**
 * Gaveta com uma lista completa da ficha (pode ter milhares de itens, ex.:
 * edições): rolagem reciclada e busca por nome.
 */
final class RefListSheet {

    interface OnRefClick {
        void onClick(ApiRef ref);
    }

    static void show(Context context, String title, List<ApiRef> refs, OnRefClick listener) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        SheetRefListBinding b = SheetRefListBinding.inflate(LayoutInflater.from(context));
        b.tvSheetTitle.setText(title);
        b.tvSheetCount.setText(DetailFormat.number(refs.size()));

        Adapter adapter = new Adapter(context, refs, ref -> {
            listener.onClick(ref);
        });
        b.sheetList.setAdapter(adapter);
        b.etSheetSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s == null ? "" : s.toString());
                b.tvSheetEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            }
        });

        dialog.setContentView(b.getRoot());
        int height = context.getResources().getDisplayMetrics().heightPixels;
        b.getRoot().setMinimumHeight((int) (height * 0.85f));
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        dialog.show();
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final Context context;
        private final List<ApiRef> all;
        private final OnRefClick listener;
        /** Posições (em {@link #all}) visíveis com a busca atual. */
        private final List<Integer> visible = new ArrayList<>();

        Adapter(Context context, List<ApiRef> all, OnRefClick listener) {
            this.context = context;
            this.all = all;
            this.listener = listener;
            filter("");
        }

        @SuppressWarnings("NotifyDataSetChanged")
        void filter(String query) {
            String needle = SearchText.fold(query);
            visible.clear();
            for (int i = 0; i < all.size(); i++) {
                ApiRef ref = all.get(i);
                if (needle.isEmpty() || SearchText.fold(ref == null ? null : ref.name).contains(needle)) visible.add(i);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemRefRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            int index = visible.get(position);
            ApiRef ref = all.get(index);
            holder.binding.tvIndex.setText(context.getString(R.string.hero_list_index, index + 1));
            holder.binding.tvRefName.setText(DetailFormat.refName(context, ref));
            boolean hasPage = ref != null && !DetailFormat.isBlank(ref.siteDetailUrl);
            holder.binding.iconOpen.setVisibility(hasPage ? View.VISIBLE : View.INVISIBLE);
            holder.binding.getRoot().setClickable(hasPage);
            holder.binding.getRoot().setOnClickListener(hasPage ? v -> listener.onClick(ref) : null);
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemRefRowBinding binding;

            Holder(ItemRefRowBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private RefListSheet() { }
}
