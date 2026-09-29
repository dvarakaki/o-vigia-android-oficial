package com.ovigia.app.ui.game;

import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentArtPanelBinding;
import com.ovigia.app.databinding.PanelGuessBinding;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.WatcherArt;

/** O motor arrisca um personagem; o jogador confirma ou rejeita. */
public class GuessFragment extends GameFragment {

    public static final String ARG_CHARACTER_ID = "characterId";

    private PanelGuessBinding panel;
    private final Motion motion = new Motion();
    private boolean bound = false;

    public GuessFragment() {
        super(R.layout.fragment_art_panel);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentArtPanelBinding binding = FragmentArtPanelBinding.bind(view);
        panel = PanelGuessBinding.inflate(getLayoutInflater(), binding.panelContainer, true);
        super.onViewCreated(view, savedInstanceState);

        int characterId = requireArguments().getInt(ARG_CHARACTER_ID, -1);

        // Voltar do chute = "espera, deixa eu rever minha última resposta": desfaz
        // a resposta que levou ao chute e mostra a mesma pergunta de novo.
        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isCurrentDestination(R.id.guessFragment)) return;
                viewModel.goBackOneQuestion();
                nav().popBackStack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        binding.btnBack.setOnClickListener(v -> back.handleOnBackPressed());

        panel.btnSim.setOnClickListener(v -> {
            if (!isCurrentDestination(R.id.guessFragment)) return;
            answerFeedback();
            viewModel.confirmGuess();
        });
        panel.btnNao.setOnClickListener(v -> {
            if (!isCurrentDestination(R.id.guessFragment)) return;
            answerFeedback();
            viewModel.rejectGuess();
            nav().navigate(R.id.continueFragment, null, GameNavigator.overQuestions());
        });

        setButtonsEnabled(false);
        WatcherArt art = new WatcherArt(binding.imageArt, motion);
        viewModel.state().observe(getViewLifecycleOwner(), state -> {
            // Convicto ao chutar; ao ouvir "não", reage antes de a tela sair.
            art.show(state.mood);
            if (!bound && state.isLoaded()) bound = bind(characterId);
        });
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        panel = null;
        bound = false;
    }

    private boolean bind(int characterId) {
        CharacterProfile guess = viewModel.getProfile(characterId);
        if (guess == null) {
            nav().popBackStack();
            return false;
        }
        panel.tvGuessName.setText(guess.name);
        panel.imageGuess.setContentDescription(guess.name);
        Glide.with(this)
                .load(guess.imageUrl)
                .placeholder(R.drawable.ic_character_placeholder)
                .error(R.drawable.ic_character_placeholder)
                // CenterCrop + RoundedCorners juntos: encadear separado aplica só a última.
                .transform(new MultiTransformation<>(new CenterCrop(),
                        new RoundedCorners(getResources().getDimensionPixelSize(R.dimen.gap) * 2)))
                .into(panel.imageGuess);
        setButtonsEnabled(true);
        return true;
    }

    private void setButtonsEnabled(boolean enabled) {
        panel.btnSim.setEnabled(enabled);
        panel.btnNao.setEnabled(enabled);
    }
}
