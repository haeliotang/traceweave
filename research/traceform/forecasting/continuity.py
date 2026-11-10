# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Representation and stream continuity checks for adjacent frame pairs."""


def same_stream_segment(first, second):
    for field in ("model_version", "boot_id", "continuity_epoch", "source_mode"):
        if first.get(field) != second.get(field):
            return False
    return second.get("continuous_from_previous") is not False
