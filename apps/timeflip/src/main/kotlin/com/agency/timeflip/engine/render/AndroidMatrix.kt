package com.agency.timeflip.engine.render

import android.graphics.Matrix
import com.agency.timeflip.domain.geometry.AffineMatrix

/** Copies domain affine matrices into android.graphics.Matrix without allocating. */
internal object AndroidMatrix {

    /**
     * Android maps x' = MSCALE_X·x + MSKEW_X·y + MTRANS_X and y' = MSKEW_Y·x + MSCALE_Y·y + MTRANS_Y.
     * The domain maps x' = a·x + c·y + e and y' = b·x + d·y + f, so MSCALE_X = a, MSKEW_X = c,
     * MTRANS_X = e, MSKEW_Y = b, MSCALE_Y = d, MTRANS_Y = f. The array order in setValues is
     * [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, MPERSP_0, MPERSP_1, MPERSP_2],
     * which is [a, c, e, b, d, f, 0, 0, 1]. Using the named indices keeps that order visible.
     */
    fun set(matrix: Matrix, values: FloatArray, m: AffineMatrix) {
        values[Matrix.MSCALE_X] = m.a.toFloat()
        values[Matrix.MSKEW_X] = m.c.toFloat()
        values[Matrix.MTRANS_X] = m.e.toFloat()
        values[Matrix.MSKEW_Y] = m.b.toFloat()
        values[Matrix.MSCALE_Y] = m.d.toFloat()
        values[Matrix.MTRANS_Y] = m.f.toFloat()
        values[Matrix.MPERSP_0] = 0f
        values[Matrix.MPERSP_1] = 0f
        values[Matrix.MPERSP_2] = 1f
        matrix.setValues(values)
    }
}
