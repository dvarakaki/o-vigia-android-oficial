package com.ovigia.app.ui;

import android.content.res.Resources;
import android.graphics.Bitmap;

import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.ovigia.app.R;

/** Retratos de personagem: recorte central com os cantos arredondados. */
public final class Portraits {

    /** CenterCrop + RoundedCorners juntos: encadear separado aplica só a última. */
    public static Transformation<Bitmap> roundedCrop(int radiusPx) {
        return new MultiTransformation<>(new CenterCrop(), new RoundedCorners(radiusPx));
    }

    /** Canto dos retratos em destaque (chute, resultado) e das listas de escolha. */
    public static int cardRadius(Resources res) {
        return res.getDimensionPixelSize(R.dimen.gap) * 2;
    }

    private Portraits() { }
}
