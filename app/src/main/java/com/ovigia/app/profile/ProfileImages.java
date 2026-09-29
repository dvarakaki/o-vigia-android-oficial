package com.ovigia.app.profile;

import android.net.Uri;

import com.ovigia.app.auth.AccountStore.ImageKind;

import java.io.File;
import java.io.IOException;

/**
 * Foto e banner do perfil no formato em que vão para a conta online: JPEG
 * reduzido e codificado em Base64, pequeno o bastante para caber no documento
 * (e aparecer igual em qualquer aparelho). Abstraída para os ViewModels serem
 * testados na JVM sem decodificar imagens de verdade.
 *
 * Operações bloqueantes: chamar fora da main thread.
 */
public interface ProfileImages {

    /** A imagem escolhida pelo jogador, já reduzida para {@code kind}. */
    String encode(Uri source, ImageKind kind) throws IOException;

    /** O mesmo, a partir de um arquivo (as fotos guardadas pelas versões antigas). */
    String encodeFile(File file, ImageKind kind) throws IOException;
}
