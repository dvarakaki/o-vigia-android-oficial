package com.ovigia.app.ui.game;

import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentArtPanelBinding;
import com.ovigia.app.databinding.PanelContinueBinding;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.WatcherArt;

/**
 * Depois de um chute rejeitado: continuar respondendo ou escolher entre alternativas?
 * O Vigia aparece irritado ou desconfiado, conforme o quanto confiava no chute.
 */
public class ContinueFragment extends GameFragment {

    private final Motion motion = new Motion();

    public ContinueFragment() {
        super(R.layout.fragment_art_panel);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentArtPanelBinding binding = FragmentArtPanelBinding.bind(view);
        PanelContinueBinding panel = PanelContinueBinding.inflate(getLayoutInflater(), binding.panelContainer, true);
        super.onViewCreated(view, savedInstanceState);

        // Voltar daqui é o mesmo que "sim, continuar": a partida segue perguntando.
        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isCurrentDestination(R.id.continueFragment)) viewModel.continueGuessing();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        binding.btnBack.setOnClickListener(v -> back.handleOnBackPressed());

        panel.btnSim.setOnClickListener(v -> {
            if (!isCurrentDestination(R.id.continueFragment)) return;
            answerFeedback();
            back.handleOnBackPressed();
        });
        panel.btnNao.setOnClickListener(v -> {
            if (!isCurrentDestination(R.id.continueFragment)) return;
            answerFeedback();
            viewModel.stopGuessing();
        });

        WatcherArt art = new WatcherArt(binding.imageArt, motion);
        viewModel.state().observe(getViewLifecycleOwner(), state -> {
            art.show(state.mood);
            panel.btnSim.setEnabled(state.isLoaded());
            panel.btnNao.setEnabled(state.isLoaded());
        });
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
    }
}
