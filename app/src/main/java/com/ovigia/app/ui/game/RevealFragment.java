package com.ovigia.app.ui.game;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ovigia.app.R;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.util.SearchText;

import java.util.ArrayList;
import java.util.List;

/**
 * O motor perdeu: o jogador conta em quem pensou. É a fonte de aprendizado mais
 * valiosa — justamente as partidas em que o motor errou.
 */
public class RevealFragment extends CharacterListFragment {

    private List<CharacterProfile> all = new ArrayList<>();

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.tvTitle.setText(R.string.reveal_title);
        binding.tvSubtitle.setText(R.string.reveal_subtitle);
        binding.tvSubtitle.setVisibility(View.VISIBLE);
        binding.searchLayout.setVisibility(View.VISIBLE);
        binding.btnPrimary.setText(R.string.btn_validar);
        binding.btnSecondary.setText(R.string.btn_skip);
        binding.btnSecondary.setOnClickListener(v -> {
            if (isCurrentDestination(R.id.revealFragment)) viewModel.giveUp();
        });
        binding.btnBack.setOnClickListener(v -> nav().popBackStack());
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
    }

    @Override
    protected int destinationId() {
        return R.id.revealFragment;
    }

    @Override
    protected void onCharactersAvailable() {
        all = viewModel.allCharacters();
        applyFilter();
    }

    @Override
    protected void onConfirm(int characterId) {
        viewModel.reveal(characterId);
    }

    private void applyFilter() {
        if (binding == null) return;
        String query = SearchText.fold(String.valueOf(binding.etSearch.getText()));
        List<CharacterProfile> filtered = new ArrayList<>();
        for (CharacterProfile c : all) {
            if (query.isEmpty() || SearchText.fold(c.name).contains(query)) filtered.add(c);
        }
        show(filtered);
    }

}
