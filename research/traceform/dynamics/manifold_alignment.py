# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Local entropic Gromov-Wasserstein alignment of metric structures.

Distance matrices are sensitive derived data. This is not a cryptographic protocol.
"""

import math
import random


def logsumexp(values):
    maximum = max(values)
    return maximum + math.log(sum(math.exp(value - maximum) for value in values))


def round_transport(coupling):
    """Restore exact uniform marginals after a finite Sinkhorn iteration."""
    rows, columns = len(coupling), len(coupling[0])
    result = [[value * min(1.0, (1 / rows) / sum(row)) for value in row] for row in coupling]
    scales = [min(1.0, (1 / columns) / sum(result[row][column] for row in range(rows))) for column in range(columns)]
    result = [[value * scales[column] for column, value in enumerate(row)] for row in result]
    row_deficit = [max(0.0, 1 / rows - sum(row)) for row in result]
    column_deficit = [max(0.0, 1 / columns - sum(result[row][column] for row in range(rows))) for column in range(columns)]
    mass = sum(row_deficit)
    if mass > 1e-15:
        result = [[value + row_deficit[row] * column_deficit[column] / mass for column, value in enumerate(values)] for row, values in enumerate(result)]
    return result


def sinkhorn(cost, epsilon, iterations=3000):
    rows, columns = len(cost), len(cost[0])
    log_kernel = [[-value / epsilon for value in row] for row in cost]
    u, v = [0.0] * rows, [0.0] * columns
    for iteration in range(iterations):
        u = [-math.log(rows) - logsumexp([log_kernel[row][column] + v[column] for column in range(columns)]) for row in range(rows)]
        v = [-math.log(columns) - logsumexp([log_kernel[row][column] + u[row] for row in range(rows)]) for column in range(columns)]
        if iteration % 20 == 19:
            error = max(abs(math.exp(u[row] + logsumexp([log_kernel[row][column] + v[column] for column in range(columns)])) - 1 / rows) for row in range(rows))
            if error < 1e-9:
                break
    return round_transport([[math.exp(log_kernel[row][column] + u[row] + v[column]) for column in range(columns)] for row in range(rows)])


def gw_cost(first, second, coupling):
    rows, columns = len(first), len(second)
    left = [sum(value * value for value in row) / rows for row in first]
    right = [sum(value * value for value in row) / columns for row in second]
    product = [[sum(first[row][index] * coupling[index][column] for index in range(rows)) for column in range(columns)] for row in range(rows)]
    return [[left[row] + right[column] - 2 * sum(product[row][index] * second[column][index] for index in range(columns)) for column in range(columns)] for row in range(rows)]


def align_metric_spaces(first, second, epsilon=0.01, iterations=100, restarts=4, seed=42):
    if epsilon <= 0 or iterations < 1 or restarts < 1:
        raise ValueError("positive regularization, iteration and restart counts required")
    for matrix in (first, second):
        if not matrix or any(len(row) != len(matrix) for row in matrix):
            raise ValueError("nonempty square distance matrices required")
        for row in range(len(matrix)):
            for column in range(len(matrix)):
                if not math.isfinite(matrix[row][column]) or matrix[row][column] < 0 or abs(matrix[row][column] - matrix[column][row]) > 1e-8:
                    raise ValueError("finite nonnegative symmetric distances required")
            if abs(matrix[row][row]) > 1e-8:
                raise ValueError("distance diagonal must be zero")
    rows, columns = len(first), len(second)
    rng = random.Random(seed)
    best = None
    for restart in range(restarts):
        if restart == 0:
            # Permutation-invariant row signatures avoid the uniform saddle.
            signatures_first = [[sorted(row)[round(index * (rows - 1) / 15)] for index in range(16)] for row in first]
            signatures_second = [[sorted(row)[round(index * (columns - 1) / 15)] for index in range(16)] for row in second]
            initial_cost = [[sum((left - right) ** 2 for left, right in zip(a, b)) / 16 for b in signatures_second] for a in signatures_first]
            coupling = sinkhorn(initial_cost, epsilon)
        else:
            coupling = sinkhorn([[rng.random() for _ in range(columns)] for _ in range(rows)], 0.02)
        residual = math.inf
        for iteration in range(iterations):
            cost = gw_cost(first, second, coupling)
            updated = sinkhorn([[2 * value for value in row] for row in cost], epsilon)
            residual = max(abs(updated[row][column] - coupling[row][column]) for row in range(rows) for column in range(columns))
            coupling = updated
            if residual < 1e-8:
                break
        cost = gw_cost(first, second, coupling)
        objective = max(0.0, sum(cost[row][column] * coupling[row][column] for row in range(rows) for column in range(columns)))
        row_error = max(abs(sum(row) - 1 / rows) for row in coupling)
        column_error = max(abs(sum(coupling[row][column] for row in range(rows)) - 1 / columns) for column in range(columns))
        result = {"squared_gw_objective": objective, "coupling": coupling,
                  "marginal_error": max(row_error, column_error), "fixed_point_residual": residual,
                  "iterations": iteration + 1, "epsilon": epsilon,
                  "evidence_scope": "local_nonconvex_structure_alignment_not_zero_knowledge"}
        if best is None or objective < best["squared_gw_objective"]:
            best = result
    best["converged"] = best["marginal_error"] < 1e-6 and best["fixed_point_residual"] < 1e-6
    return best


def node_distance_matrix(model):
    centers = [node["center_x"] for node in model["nodes"]]
    if not centers or any(len(center) != len(centers[0]) for center in centers):
        raise ValueError("consistent model centers required")
    distances = [[math.sqrt(sum((left - right) ** 2 for left, right in zip(first, second))) for second in centers] for first in centers]
    diameter = max(max(row) for row in distances)
    return [[value / diameter if diameter > 0 else 0.0 for value in row] for row in distances]
