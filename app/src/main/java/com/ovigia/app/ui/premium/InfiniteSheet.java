package com.ovigia.app.ui.premium;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.databinding.SheetInfiniteBinding;
import com.ovigia.app.premium.InfiniteState;
import com.ovigia.app.premium.InfiniteWatcher;
import com.ovigia.app.ui.RarityViews;
import com.ovigia.app.util.Event;

/**
 * Gaveta do Vigia do Infinito: o que a compra libera, o preço que o Google Play
 * informou e o andamento da compra. Abre do resultado (um lendário ficou
 * lacrado), do catálogo e das configurações.
 *
 * A fonte da verdade é o {@link InfiniteWatcher} do app: a gaveta só desenha o
 * estado dele. Se ela fechar no meio da compra, a compra segue, e as telas por
 * baixo reagem ao estado novo.
 */
public final class InfiniteSheet {

    /** Gavetas abertas agora: com uma aberta, os avisos da compra aparecem nela, não por baixo. */
    private static int openCount = 0;

    /** Se há uma gaveta aberta (main thread). */
    public static boolean isOpen() {
        return openCount > 0;
    }

    /**
     * @param found raridade do personagem que o jogador tentou desbloquear, ou {@code null}
     *              quando a gaveta abre sem um personagem em jogo (configurações)
     */
    public static void show(Fragment host, @Nullable Rarity found) {
        if (!host.isAdded() || host.getView() == null) return;
        Context context = host.requireContext();
        AppContainer container = ((OVigiaApplication) host.requireActivity().getApplication()).container();
        InfiniteWatcher infinite = container.infinite;

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        SheetInfiniteBinding b = SheetInfiniteBinding.inflate(LayoutInflater.from(context));
        if (found != null) {
            b.tvInfiniteFound.setVisibility(View.VISIBLE);
            b.tvInfiniteFound.setText(context.getString(R.string.infinite_sheet_found,
                    context.getString(RarityViews.label(found))));
        }

        // Lendários que já esperam lacrados: entram na hora da compra.
        String account = container.accountStore.currentAccountId();
        if (account != null) {
            container.ioExecutor.execute(() -> {
                int waiting = container.collectionStore.listSealed(account).size();
                container.mainExecutor.execute(() -> {
                    if (waiting == 0 || !dialog.isShowing()) return;
                    b.tvBenefitSealed.setVisibility(View.VISIBLE);
                    b.tvBenefitSealed.setText(context.getResources()
                            .getQuantityString(R.plurals.infinite_benefit_sealed, waiting, waiting));
                });
            });
        }

        b.btnInfiniteBuy.setOnClickListener(v -> {
            if (host.isAdded()) infinite.purchase(host.requireActivity());
        });

        LifecycleOwner owner = host.getViewLifecycleOwner();
        Observer<InfiniteState> stateObserver = state -> render(b, state, infinite, dialog);
        Observer<Event<InfiniteWatcher.Notice>> noticeObserver = event -> {
            // A abertura da gaveta não deve mostrar um aviso antigo.
            if (!dialog.isShowing()) return;
            InfiniteWatcher.Notice notice = event.consume();
            if (notice == InfiniteWatcher.Notice.OFFLINE || notice == InfiniteWatcher.Notice.FAILED) {
                b.statusRow.setVisibility(View.VISIBLE);
                b.statusProgress.setVisibility(View.GONE);
                b.tvInfiniteStatus.setText(notice == InfiniteWatcher.Notice.OFFLINE
                        ? R.string.infinite_notice_offline : R.string.infinite_notice_failed);
            }
        };
        // A tela por baixo foi embora (girou, saiu): a gaveta vai junto.
        DefaultLifecycleObserver closeWithHost = new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner source) {
                dialog.dismiss();
            }
        };

        dialog.setOnShowListener(d -> openCount++);
        dialog.setOnDismissListener(d -> {
            openCount = Math.max(0, openCount - 1);
            infinite.state().removeObserver(stateObserver);
            infinite.notices().removeObserver(noticeObserver);
            owner.getLifecycle().removeObserver(closeWithHost);
        });
        dialog.setContentView(b.getRoot());
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        dialog.show();

        infinite.state().observe(owner, stateObserver);
        infinite.notices().observe(owner, noticeObserver);
        owner.getLifecycle().addObserver(closeWithHost);
        // Preço e compra frescos: o jogador pode ter comprado em outro aparelho.
        infinite.refresh();
    }

    private static void render(SheetInfiniteBinding b, InfiniteState state, InfiniteWatcher infinite,
                               BottomSheetDialog dialog) {
        Context context = b.getRoot().getContext();
        boolean owned = state.isInfinite();
        b.tvInfiniteTitle.setText(owned ? R.string.infinite_owned_title : R.string.infinite_sheet_title);
        b.tvInfiniteSubtitle.setText(owned ? R.string.infinite_owned_body : R.string.infinite_sheet_subtitle);
        b.benefits.setVisibility(owned ? View.GONE : View.VISIBLE);
        if (owned) b.tvInfiniteFound.setVisibility(View.GONE);

        int status;
        boolean busy = false;
        switch (state.status) {
            case CHECKING:
                status = R.string.infinite_checking;
                busy = true;
                break;
            case PENDING:
                status = R.string.infinite_pending;
                break;
            case SIGNED_OUT:
                status = R.string.infinite_signed_out;
                break;
            case OWNED_BY_OTHER_ACCOUNT:
                status = R.string.infinite_other_account;
                break;
            case UNAVAILABLE:
                status = R.string.infinite_unavailable;
                break;
            case AVAILABLE:
            case OWNED:
            default:
                status = 0;
                busy = state.purchasing;
                break;
        }
        b.statusRow.setVisibility(status != 0 || busy ? View.VISIBLE : View.GONE);
        b.statusProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.tvInfiniteStatus.setText(status != 0 ? context.getString(status) : "");

        b.btnInfiniteBuy.setVisibility(owned ? View.GONE : View.VISIBLE);
        b.btnInfiniteBuy.setEnabled(state.canPurchase());
        b.btnInfiniteBuy.setText(state.price != null
                ? context.getString(R.string.infinite_become_price, state.price)
                : context.getString(R.string.infinite_become));

        if (owned) {
            b.btnInfiniteSecondary.setVisibility(View.VISIBLE);
            b.btnInfiniteSecondary.setText(R.string.infinite_close);
            b.btnInfiniteSecondary.setOnClickListener(v -> dialog.dismiss());
        } else if (state.status == InfiniteState.Status.UNAVAILABLE) {
            b.btnInfiniteSecondary.setVisibility(View.VISIBLE);
            b.btnInfiniteSecondary.setText(R.string.infinite_retry);
            b.btnInfiniteSecondary.setOnClickListener(v -> infinite.refresh());
        } else {
            b.btnInfiniteSecondary.setVisibility(View.GONE);
        }
    }

    private InfiniteSheet() { }
}
