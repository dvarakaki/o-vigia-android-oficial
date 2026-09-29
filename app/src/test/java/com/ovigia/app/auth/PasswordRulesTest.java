package com.ovigia.app.auth;

import com.ovigia.app.auth.PasswordRules.Rule;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PasswordRulesTest {

    @Test
    public void strongPassword_needsEightCharacters_andASpecialOne() {
        assertTrue(PasswordRules.isStrong("segredo#1"));
        assertFalse("8 caracteres sem especial", PasswordRules.isStrong("segredo1"));
        assertFalse("especial, mas curta", PasswordRules.isStrong("ab#1"));
        assertFalse(PasswordRules.isStrong(""));
        assertFalse(PasswordRules.isStrong(null));
    }

    @Test
    public void eachRuleIsCheckedOnItsOwn() {
        assertEquals(EnumSet.noneOf(Rule.class), PasswordRules.satisfied("abc"));
        assertEquals(EnumSet.of(Rule.LENGTH), PasswordRules.satisfied("abcdefgh"));
        assertEquals(EnumSet.of(Rule.SPECIAL), PasswordRules.satisfied("a.b"));
        assertEquals(EnumSet.allOf(Rule.class), PasswordRules.satisfied("abc-defg"));
    }

    @Test
    public void specialMeansAnythingButLettersDigitsAndSpaces() {
        for (String special : new String[]{"!", "@", "#", "$", "%", "-", "_", ".", "*", "€", "😀"}) {
            assertTrue(special, PasswordRules.satisfied("a" + special).contains(Rule.SPECIAL));
        }
        assertFalse("espaço não conta", PasswordRules.satisfied("a b c").contains(Rule.SPECIAL));
        assertFalse("acento é letra", PasswordRules.satisfied("ação").contains(Rule.SPECIAL));
    }

    @Test
    public void lengthCountsCharacters_notJavaChars() {
        // Emoji ocupa dois char em Java, mas é um caractere para quem digita.
        assertFalse(PasswordRules.satisfied("😀😀😀😀").contains(Rule.LENGTH));
        assertTrue(PasswordRules.satisfied("😀😀😀😀😀😀😀😀").contains(Rule.LENGTH));
    }

    @Test
    public void progress_growsWithEachCharacter_andJumpsWithTheSpecialOne() {
        assertEquals(0f, PasswordRules.progress(""), 0.001f);
        assertEquals(0.25f, PasswordRules.progress("abcd"), 0.001f);
        assertEquals(0.5f, PasswordRules.progress("abcdefgh"), 0.001f);
        assertEquals("o especial vale a outra metade", 0.75f, PasswordRules.progress("abcd#"), 0.07f);
        assertEquals(1f, PasswordRules.progress("abcdefg#"), 0.001f);
        assertEquals("passar do mínimo não passa de 1", 1f, PasswordRules.progress("abcdefghijk#"), 0.001f);
    }
}
