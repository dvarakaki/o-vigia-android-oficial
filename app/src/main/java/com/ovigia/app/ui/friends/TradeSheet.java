package com.ovigia.app.ui.friends;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ovigia.app.R;
import com.ovigia.app.databinding.ItemCatalogHeroBinding;
import com.ovigia.app.databinding.SheetTradeBinding;
import com.ovigia.app.social.PublicProfile;
import com.ovigia.app.social.TradeSuggestions;
import com.ovigia.app.ui.achievements.AchievementArt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gaveta da troca de heróis, 1 por 1, nos dois lados: quem propõe escolhe o que
 * oferecer; quem responde escolhe qual herói do catálogo do amigo quer ganhar.
 * Em cima fica o que cada um dá e recebe; embaixo, a grade do lado que varia.
 *
 * A gaveta só desenha o {@link Model}: o estado vive no ViewModel da tela, que
 * reabre a gaveta depois de girar o aparelho.
 */
final class TradeSheet {

    interface Actions {
        void onPick(int heroId);

        void onConfirm();

        void onSecondary();

        /** O jogador fechou a gaveta (arrastando, voltando ou tocando fora). */
        void onClosed();
    }

    /** O que a gaveta mostra. */
    static final class Model {
        CharSequence title;
        /** O que o jogador dá e o que recebe; {@code null} deixa a carta vazia. */
        @Nullable PublicProfile.Hero give;
        @Nullable PublicProfile.Hero get;
        @Nullable CharSequence giveReason;
        @Nullable CharSequence getReason;
        CharSequence pickerTitle;
        @Nullable CharSequence pickerNote;
        List<TradeSuggestions.Pick> picks = Collections.emptyList();
        int selectedId = -1;
        /** Herói marcado com {@link #badgeText} (o sugerido, ou o que o amigo ofereceu). */
        int badgeId = -1;
        @Nullable CharSequence badgeText;
        boolean loading;
        boolean busy;
        boolean confirmEnabled;
        @StringRes int confirmText;
        @StringRes int secondaryText;
    }

    private final Fragment host;
    private final BottomSheetDialog dialog;
    private final SheetTradeBinding binding;
    private final Adapter adapter;
    private boolean closingFromCode = false;

    TradeSheet(Fragment host, Actions actions) {
        this.host = host;
        Context context = host.requireContext();
        dialog = new BottomSheetDialog(context);
        binding = SheetTradeBinding.inflate(LayoutInflater.from(context));
        adapter = new Adapter(host, actions);

        int minCard = context.getResources().getDimensionPixelSize(R.dimen.friend_hero_min_width);
        int width = Math.min(context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDimensionPixelSize(R.dimen.content_max_width));
        binding.pickerGrid.setLayoutManager(new GridLayoutManager(context, Math.max(3, width / minCard)));
        binding.pickerGrid.setAdapter(adapter);
        binding.btnTradeConfirm.setOnClickListener(v -> actions.onConfirm());
        binding.btnTradeSecondary.setOnClickListener(v -> actions.onSecondary());

        dialog.setContentView(binding.getRoot());
        dialog.setOnDismissListener(d -> {
            if (!closingFromCode) actions.onClosed();
        });
        int height = context.getResources().getDisplayMetrics().heightPixels;
        binding.getRoot().setMinimumHeight((int) (height * 0.85f));
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        dialog.show();
    }

    /** Fecha sem avisar o ViewModel (a tela saiu, ou o estado já fechou a troca). */
    void dismissQuietly() {
        closingFromCode = true;
        dialog.dismiss();
    }

    void bind(Model model) {
        Context context = host.requireContext();
        binding.tvTradeTitle.setText(model.title);
        bindSlot(binding.slotGive, model.give);
        bindSlot(binding.slotGet, model.get);
        bindReason(binding.tvGiveReason, model.giveReason);
        bindReason(binding.tvGetReason, model.getReason);

        binding.tvPickerTitle.setText(model.pickerTitle);
        binding.tvPickerNote.setVisibility(model.pickerNote != null ? View.VISIBLE : View.GONE);
        binding.tvPickerNote.setText(model.pickerNote);
        binding.pickerProgress.setVisibility(model.loading ? View.VISIBLE : View.GONE);
        adapter.submit(model.loading ? Collections.emptyList() : model.picks, model.selectedId, model.badgeId,
                model.badgeText, !model.busy);

        binding.btnTradeConfirm.setText(model.busy ? null : context.getString(model.confirmText));
        binding.btnTradeConfirm.setEnabled(model.confirmEnabled && !model.busy && !model.loading);
        binding.confirmProgress.setVisibility(model.busy ? View.VISIBLE : View.GONE);
        binding.btnTradeSecondary.setText(model.secondaryText);
        binding.btnTradeSecondary.setEnabled(!model.busy);
        dialog.setCancelable(!model.busy);
    }

    private void bindSlot(ItemCatalogHeroBinding slot, @Nullable PublicProfile.Hero hero) {
        bindCard(host, slot, hero, false, null);
        slot.card.setClickable(false);
        slot.card.setFocusable(false);
    }

    private static void bindReason(TextView view, @Nullable CharSequence reason) {
        view.setText(reason);
        view.setVisibility(reason != null ? View.VISIBLE : View.INVISIBLE);
    }

    /** Carta de herói da troca: foto, nome e, se for o caso, o selo e o contorno de escolhida. */
    static void bindCard(Fragment host, ItemCatalogHeroBinding card, @Nullable PublicProfile.Hero hero,
                         boolean selected, @Nullable CharSequence badge) {
        Context context = host.requireContext();
        card.lockedOverlay.setVisibility(hero == null ? View.VISIBLE : View.GONE);
        String name = heroName(context, hero);
        card.tvName.setText(hero == null ? "" : name);
        card.card.setContentDescription(hero == null ? null : name);
        card.card.setStrokeColor(ContextCompat.getColor(context, selected ? R.color.vigia_gold : R.color.white_10));
        card.card.setStrokeWidth(context.getResources().getDimensionPixelSize(
                selected ? R.dimen.catalog_preview_ring : R.dimen.trade_card_stroke));
        card.tvBadge.setVisibility(badge != null ? View.VISIBLE : View.GONE);
        card.tvBadge.setText(badge);
        Glide.with(host)
                .load(hero == null ? null : hero.imageUrl)
                .placeholder(R.color.vigia_panel_solid)
                .error(R.drawable.ic_character_placeholder)
                .centerCrop()
                .into(card.image);
    }

    static String heroName(Context context, @Nullable PublicProfile.Hero hero) {
        return hero != null && hero.name != null ? hero.name : context.getString(R.string.profile_unknown_character);
    }

    /** Por que a sugestão vale a pena, para quem vai ganhar o herói; {@code null} se não há o que dizer. */
    @Nullable
    static String reasonText(Context context, @Nullable TradeSuggestions.Pick pick) {
        if (pick == null) return null;
        switch (pick.reason) {
            case COMPLETES:
                return context.getString(R.string.trade_reason_completes,
                        context.getString(AchievementArt.titleOf(pick.achievement)));
            case ADVANCES:
                return context.getString(R.string.trade_reason_advances,
                        context.getString(AchievementArt.titleOf(pick.achievement)));
            case POPULAR:
                return context.getString(R.string.trade_reason_popular);
            case NONE:
            default:
                return null;
        }
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final Fragment host;
        private final Actions actions;
        private List<TradeSuggestions.Pick> picks = new ArrayList<>();
        private int selectedId = -1;
        private int badgeId = -1;
        @Nullable private CharSequence badgeText;
        private boolean enabled = true;

        Adapter(Fragment host, Actions actions) {
            this.host = host;
            this.actions = actions;
        }

        @SuppressWarnings("NotifyDataSetChanged")
        void submit(List<TradeSuggestions.Pick> newPicks, int newSelectedId, int newBadgeId,
                    @Nullable CharSequence newBadgeText, boolean newEnabled) {
            picks = new ArrayList<>(newPicks);
            selectedId = newSelectedId;
            badgeId = newBadgeId;
            badgeText = newBadgeText;
            enabled = newEnabled;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemCatalogHeroBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            TradeSuggestions.Pick pick = picks.get(position);
            int id = pick.hero.characterId;
            bindCard(host, holder.binding, pick.hero, id == selectedId, id == badgeId ? badgeText : null);
            holder.binding.card.setSelected(id == selectedId);
            holder.binding.card.setEnabled(enabled);
            holder.binding.card.setOnClickListener(v -> actions.onPick(id));
        }

        @Override
        public int getItemCount() {
            return picks.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemCatalogHeroBinding binding;

            Holder(ItemCatalogHeroBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
