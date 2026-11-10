// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

final class WarmBucket {
    final long bucket;
    final int frames;
    final boolean continuous;
    final long missingBefore;
    WarmBucket(long bucket, int frames, boolean continuous, long missingBefore) {
        this.bucket = bucket; this.frames = frames; this.continuous = continuous; this.missingBefore = missingBefore;
    }
    static WarmBucket next(long millis, boolean continuous, WarmBucket previous) {
        long bucket = Math.floorDiv(millis, 30000) * 30000;
        if (previous == null) return new WarmBucket(bucket, 1, false, 0);
        if (bucket == previous.bucket) return new WarmBucket(bucket, previous.frames + 1,
            previous.continuous && continuous, previous.missingBefore);
        long missing = Math.max(0, (bucket - previous.bucket) / 30000 - 1);
        return new WarmBucket(bucket, 1, continuous && bucket > previous.bucket && missing == 0, missing);
    }
}
