package com.ovigia.app.data.roster;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Elenco jogável e classificações curadas à mão (times, poderes, crença de
 * vilania, reconhecimento mainstream), lidos de {@code assets/roster.json}.
 *
 * A Comic Vine não expõe esses dados de forma utilizável no endpoint de
 * lista ({@code teams}/{@code powers} só existem no detalhe, um personagem por
 * vez, e a taxonomia de poderes é inconsistente demais para boas perguntas).
 * Por isso a curadoria vive num arquivo de dados — editável sem recompilar
 * lógica e validado por {@code RosterCatalogTest}.
 *
 * Classe Java pura (sem Android) para poder ser testada na JVM.
 */
public final class RosterCatalog {

    private final Map<Integer, Entry> entriesById;

    private RosterCatalog(Map<Integer, Entry> entriesById) {
        this.entriesById = entriesById;
    }

    /** Lê o JSON do elenco. Lança {@link JsonParseException} se o arquivo estiver malformado. */
    public static RosterCatalog parse(Reader reader) {
        Document doc = new Gson().fromJson(reader, Document.class);
        if (doc == null || doc.characters == null || doc.characters.isEmpty()) {
            throw new JsonParseException("roster.json sem personagens");
        }
        Map<Integer, Entry> byId = new LinkedHashMap<>();
        for (Entry e : doc.characters) {
            if (e.teams == null) e.teams = Collections.emptyList();
            if (e.powers == null) e.powers = Collections.emptyList();
            if (byId.put(e.id, e) != null) {
                throw new JsonParseException("id duplicado no roster.json: " + e.id);
            }
        }
        return new RosterCatalog(Collections.unmodifiableMap(byId));
    }

    /**
     * O elenco de {@code source}, ou {@code null} se ele não pôde ser lido: quem
     * usa só as classificações (conquistas, sugestões de troca) segue sem elas.
     */
    @Nullable
    public static RosterCatalog orNull(Supplier<RosterCatalog> source) {
        try {
            return source.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Entrada curada do personagem, ou {@code null} se ele não faz parte do elenco. */
    public Entry get(int characterId) {
        return entriesById.get(characterId);
    }

    public int size() {
        return entriesById.size();
    }

    @VisibleForTesting
    public Iterable<Entry> entries() {
        return entriesById.values();
    }

    /** IDs no formato de filtro da Comic Vine: {@code "1|2|3"}. */
    public String idFilter() {
        StringJoiner joiner = new StringJoiner("|");
        for (Integer id : entriesById.keySet()) joiner.add(String.valueOf(id));
        return joiner.toString();
    }

    /** Formato serializado de {@code roster.json}. */
    private static final class Document {
        int version;
        List<Entry> characters;
    }

    public static final class Entry {
        @SerializedName("id") public int id;
        /** Nome só para leitura humana do arquivo; o jogo usa o nome vindo da Comic Vine. */
        @SerializedName("name") public String name;
        @SerializedName("teams") public List<String> teams;
        @SerializedName("powers") public List<String> powers;
        /** Crença [0,1] em "é vilão?" — valores intermediários para anti-heróis. */
        @SerializedName("villain") public double villain;
        /** Reconhecimento de público casual (MCU, X-Men clássicos, vilões-símbolo). */
        @SerializedName("mainstream") public boolean mainstream;
    }
}
