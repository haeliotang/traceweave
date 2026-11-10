// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

final class CalibrationGate {
    static boolean accepts(double baselineDistance, double candidateDistance, int baselineStates,
            int candidateStates, long baselineErrors, long candidateErrors) {
        return Double.isFinite(baselineDistance) && Double.isFinite(candidateDistance)
            && baselineDistance >= 0 && candidateDistance >= 0
            && baselineStates > 0 && candidateStates > 0 && baselineErrors >= 0 && candidateErrors >= 0
            && candidateDistance < baselineDistance * 0.95 && candidateErrors <= baselineErrors
            && candidateStates >= Math.min(2, baselineStates);
    }
}
