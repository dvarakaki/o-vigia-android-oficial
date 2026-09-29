package com.ovigia.app.ui.game;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentArtPanelBinding;
import com.ovigia.app.databinding.PanelQuestionsBinding;
import com.ovigia.app.engine.Answer;
import com.ovigia.app.game.GameUiState;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.WatcherArt;

/** Tela de perguntas: base da partida. O Vigia reage a cada resposta. */
public class QuestionsFragment extends GameFragment {

    /**
     * Respostas são ignoradas por este tempo depois que uma pergunta nova
     * aparece: sem isso, um toque duplo respondia também a pergunta seguinte,
     * que o jogador nem chegou a ler.
     */
    private static final long ANSWER_COOLDOWN_MS = 400;

    private PanelQuestionsBinding panel;
    private WatcherArt art;
    private final Motion motion = new Motion();
    private String renderedQuestion;
    private long questionShownAt;

    public QuestionsFragment() {
        super(R.layout.fragment_art_panel);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentArtPanelBinding binding = FragmentArtPanelBinding.bind(view);
        panel = PanelQuestionsBinding.inflate(getLayoutInflater(), binding.panelContainer, true);
        art = new WatcherArt(binding.imageArt, motion);
        super.onViewCreated(view, savedInstanceState);

        binding.btnBack.setOnClickListener(v -> nav().navigateUp());
        panel.btnPreviousQuestion.setOnClickListener(v -> viewModel.goBackOneQuestion());
        panel.btnRetry.setOnClickListener(v -> viewModel.retry());
        bindAnswer(panel.btnSim, Answer.SIM);
        bindAnswer(panel.btnProvavelmenteSim, Answer.PROVAVELMENTE_SIM);
        bindAnswer(panel.btnNaoSei, Answer.NAO_SEI);
        bindAnswer(panel.btnProvavelmenteNao, Answer.PROVAVELMENTE_NAO);
        bindAnswer(panel.btnNao, Answer.NAO);

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.onQuestionsVisible();
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        panel = null;
        art = null;
        renderedQuestion = null;
    }

    private void bindAnswer(Button button, Answer answer) {
        button.setOnClickListener(v -> {
            if (SystemClock.uptimeMillis() - questionShownAt < ANSWER_COOLDOWN_MS) return;
            if (!isCurrentDestination(R.id.questionsFragment)) return;
            answerFeedback();
            viewModel.answer(answer);
        });
    }

    private void render(GameUiState state) {
        art.show(state.mood);
        boolean asking = state.phase == GameUiState.Phase.ASKING;
        panel.progress.setVisibility(state.phase == GameUiState.Phase.LOADING ? View.VISIBLE : View.GONE);
        panel.btnRetry.setVisibility(state.phase == GameUiState.Phase.ERROR ? View.VISIBLE : View.GONE);
        panel.answers.setVisibility(state.phase == GameUiState.Phase.ERROR ? View.GONE : View.VISIBLE);
        setAnswersEnabled(asking);
        panel.btnPreviousQuestion.setEnabled(asking && state.canGoBack);
        panel.btnPreviousQuestion.setAlpha(asking && state.canGoBack ? 1f : 0.3f);

        switch (state.phase) {
            case LOADING:
                panel.tvQuestionNumber.setText(null);
                panel.tvQuestionText.setText(R.string.question_placeholder);
                break;
            case ERROR:
                panel.tvQuestionNumber.setText(null);
                panel.tvQuestionText.setText(messageFor(state.error));
                break;
            case ASKING:
                String key = state.questionNumber + "|" + state.questionText;
                boolean changed = !key.equals(renderedQuestion);
                if (changed) {
                    questionShownAt = SystemClock.uptimeMillis();
                }
                panel.tvQuestionNumber.setText(getString(R.string.question_number, state.questionNumber));
                panel.tvQuestionText.setText(state.questionText);
                // Pergunta nova depois de uma resposta: o texto troca com um movimento curto.
                if (changed && renderedQuestion != null) motion.refresh(panel.tvQuestionNumber, panel.tvQuestionText);
                if (changed) renderedQuestion = key;
                break;
            default:
                break;
        }
    }

    private void setAnswersEnabled(boolean enabled) {
        panel.btnSim.setEnabled(enabled);
        panel.btnProvavelmenteSim.setEnabled(enabled);
        panel.btnNaoSei.setEnabled(enabled);
        panel.btnProvavelmenteNao.setEnabled(enabled);
        panel.btnNao.setEnabled(enabled);
    }
}
