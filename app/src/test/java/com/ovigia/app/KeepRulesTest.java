package com.ovigia.app;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Guarda das regras do R8 ({@code src/main/keepRules/rules.keep}).
 *
 * O Gson lê e grava estes tipos por reflexão. No release, o R8 renomeia os campos
 * de qualquer classe que não tenha regra de keep e ainda troca o tipo declarado
 * ({@code Map} vira {@code HashMap}), o que apaga a assinatura genérica: depois de
 * reabrir o app, {@code Map<Integer, Integer>} volta do disco como
 * {@code Map<String, Double>} e a primeira leitura estoura com ClassCastException.
 * Os testes de unidade rodam sem o R8 e nunca veriam isso — por isso esta checagem:
 * tudo que um dos tipos abaixo alcança, direta ou indiretamente, precisa de regra.
 *
 * Ao criar um arquivo novo em JSON (ou um tipo novo dentro de um existente), inclua
 * a classe raiz aqui e a regra no rules.keep. (Os dados do jogador não passam por
 * aqui: a API, a cópia no aparelho e os arquivos das versões antigas são lidos
 * como árvore JSON, sem reflexão.)
 */
public class KeepRulesTest {

    private static final String RULES_FILE = "src/main/keepRules/rules.keep";

    /** Tipos que o Gson lê e grava; o que eles alcançam entra na conta. */
    private static final String[] GSON_ROOTS = {
            "com.ovigia.app.settings.SettingsStore$State",
            "com.ovigia.app.translation.CachedHeroTranslationRepository$Cache",
            "com.ovigia.app.data.roster.RosterCatalog$Document",
            "com.ovigia.app.model.Character",
            "com.ovigia.app.model.CharacterDetail",
            "com.ovigia.app.model.ComicVineResponse",
    };

    /** {@code -keep class X {…}}, {@code -keepclassmembers enum X {…}} etc.: nome e corpo da regra. */
    private static final Pattern RULE = Pattern.compile(
            "^-keep(?:classmembers)?(?:,[a-z]+)*\\s+(?:class|enum|interface)\\s+(\\S+)\\s*(\\{.*\\})?", Pattern.MULTILINE);

    private static final class Rule {
        final Pattern name;
        final boolean keepsFields;

        Rule(String namePattern, String body) {
            this.name = Pattern.compile(toRegex(namePattern));
            this.keepsFields = body != null && (body.contains("<fields>") || body.contains("*;"));
        }

        /** Curingas do R8: {@code **} atravessa pacotes, {@code *} não. */
        private static String toRegex(String pattern) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    boolean doubleStar = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                    regex.append(doubleStar ? ".*" : "[^.]*");
                    if (doubleStar) i++;
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return regex.toString();
        }
    }

    private static List<Rule> readRules() throws Exception {
        String text = new String(Files.readAllBytes(new File(RULES_FILE).toPath()), StandardCharsets.UTF_8);
        List<Rule> rules = new ArrayList<>();
        Matcher m = RULE.matcher(text);
        while (m.find()) rules.add(new Rule(m.group(1), m.group(2)));
        return rules;
    }

    @Test
    public void everyTypeReachableFromGsonRoots_isKeptWithItsFields() throws Exception {
        List<Rule> rules = readRules();
        assertTrue("nenhuma regra lida de " + RULES_FILE, !rules.isEmpty());

        Set<Class<?>> reachable = new LinkedHashSet<>();
        for (String root : GSON_ROOTS) collect(Class.forName(root), reachable);

        List<String> unprotected = new ArrayList<>();
        for (Class<?> type : reachable) {
            if (!isKeptWithFields(type, rules)) unprotected.add(type.getName());
        }
        assertTrue("Sem regra de keep (campos ofuscados e assinatura genérica perdida no release; "
                        + "acrescente '-keep class <nome> { <fields>; <init>(); }' ao rules.keep): " + unprotected,
                unprotected.isEmpty());
    }

    private static boolean isKeptWithFields(Class<?> type, List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.keepsFields && rule.name.matcher(type.getName()).matches()) return true;
        }
        return false;
    }

    /** Anda pelos campos de instância (com seus tipos genéricos) e junta as classes do app. */
    private static void collect(Class<?> type, Set<Class<?>> found) {
        if (!type.getName().startsWith("com.ovigia.app.") || !found.add(type)) return;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            visit(field.getGenericType(), found);
        }
    }

    private static void visit(Type type, Set<Class<?>> found) {
        if (type instanceof Class) {
            Class<?> c = (Class<?>) type;
            collect(c.isArray() ? c.getComponentType() : c, found);
        } else if (type instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) type;
            visit(p.getRawType(), found);
            for (Type argument : p.getActualTypeArguments()) visit(argument, found);
        } else if (type instanceof GenericArrayType) {
            visit(((GenericArrayType) type).getGenericComponentType(), found);
        } else if (type instanceof WildcardType) {
            for (Type bound : ((WildcardType) type).getUpperBounds()) visit(bound, found);
        }
    }
}
