"""Exact bounded arithmetic relation for a future private GW proof backend.

This validates plaintext witnesses locally. It is not a zero-knowledge proof.
"""

import math

SCALE = 1_000_000


def quantize_alignment_witness(first, second, coupling):
    """Return a private integer witness and its measured quantization errors."""
    sizes = len(first), len(second)
    metrics = []
    for matrix, size in zip((first, second), sizes):
        if not 1 <= size <= 16 or any(len(row) != size for row in matrix):
            raise ValueError("square distance structures with 1–16 nodes required")
        if any(not math.isfinite(value) or not 0 <= value <= 1 for row in matrix for value in row):
            raise ValueError("finite normalized distances required")
        for row in range(size):
            for column in range(size):
                value = matrix[row][column]
                if abs(value - matrix[column][row]) > 1e-12 or (row == column and value != 0):
                    raise ValueError("symmetric zero-diagonal floating distances required")
                if any(value > matrix[row][middle] + matrix[middle][column] + 1e-12 for middle in range(size)):
                    raise ValueError("floating metric triangle inequality required")
        metrics.append([[math.ceil(value * SCALE) for value in row] for row in matrix])
    rows, columns = sizes
    if len(coupling) != rows or any(len(row) != columns for row in coupling):
        raise ValueError("coupling shape mismatch")
    if any(not math.isfinite(value) or value < 0 for row in coupling for value in row):
        raise ValueError("finite nonnegative transport required")
    if any(abs(sum(row) - 1 / rows) > 1e-9 for row in coupling) or any(abs(sum(coupling[i][j] for i in range(rows)) - 1 / columns) > 1e-9 for j in range(columns)):
        raise ValueError("uniform transport marginals required")
    masses = [[SCALE // size + (index < SCALE % size) for index in range(size)] for size in sizes]
    transport = [[math.floor(value * SCALE) for value in row] for row in coupling]
    row_deficits = [masses[0][i] - sum(transport[i]) for i in range(rows)]
    column_deficits = [masses[1][j] - sum(transport[i][j] for i in range(rows)) for j in range(columns)]
    if min(row_deficits + column_deficits) < 0:
        raise ValueError("transport rounding exceeds target marginals")
    cells = sorted(((i, j) for i in range(rows) for j in range(columns)),
                   key=lambda cell: (-(coupling[cell[0]][cell[1]] * SCALE - transport[cell[0]][cell[1]]), cell))
    for i, j in cells:
        increment = min(row_deficits[i], column_deficits[j])
        transport[i][j] += increment
        row_deficits[i] -= increment
        column_deficits[j] -= increment
    witness = dict(first=metrics[0], second=metrics[1], coupling=transport,
                   first_mass=masses[0], second_mass=masses[1])
    statement = validate_alignment_witness(**witness)
    distance_error = max(abs(integer / SCALE - original) for matrix, original_matrix in zip(metrics, (first, second))
                         for row, original_row in zip(matrix, original_matrix) for integer, original in zip(row, original_row))
    transport_errors = [abs(transport[i][j] / SCALE - coupling[i][j]) for i in range(rows) for j in range(columns)]
    statement["quantization"] = {"maximum_distance_error": distance_error,
                                 "maximum_coupling_error": max(transport_errors),
                                 "coupling_l1_error": sum(transport_errors),
                                 "objective_error_upper_bound": 4 * distance_error + 2 * sum(transport_errors),
                                 "scope": "feasible_rounding_not_optimal_transport"}
    return witness, statement


def validate_alignment_witness(first, second, coupling, first_mass, second_mass):
    sizes = len(first), len(second)
    for matrix, size in zip((first, second), sizes):
        if not 1 <= size <= 16 or any(len(row) != size for row in matrix):
            raise ValueError("square distance structures with 1–16 nodes required")
        for row in range(size):
            for column in range(size):
                value = matrix[row][column]
                if type(value) is not int or not 0 <= value <= SCALE:
                    raise ValueError("bounded integer distances required")
                if value != matrix[column][row] or (row == column and value != 0):
                    raise ValueError("symmetric zero-diagonal distances required")
                if any(value > matrix[row][middle] + matrix[middle][column] for middle in range(size)):
                    raise ValueError("integer metric triangle inequality required")
    rows, columns = sizes
    for masses, size in zip((first_mass, second_mass), sizes):
        if len(masses) != size or any(type(value) is not int or not 0 <= value <= SCALE for value in masses) or sum(masses) != SCALE:
            raise ValueError("bounded integer masses summing to scale required")
    if len(coupling) != rows or any(len(row) != columns for row in coupling):
        raise ValueError("coupling shape mismatch")
    if any(type(value) is not int or not 0 <= value <= SCALE for row in coupling for value in row):
        raise ValueError("bounded integer transport required")
    if any(sum(coupling[row]) != first_mass[row] for row in range(rows)) or any(sum(coupling[row][column] for row in range(rows)) != second_mass[column] for column in range(columns)):
        raise ValueError("exact transport marginals required")
    numerator = sum((first[i][j] - second[k][l]) ** 2 * coupling[i][k] * coupling[j][l]
                    for i in range(rows) for j in range(rows) for k in range(columns) for l in range(columns))
    return {"protocol": "traceform.gw-arithmetic.v1", "scale": SCALE,
            "first_nodes": rows, "second_nodes": columns,
            "objective_numerator": numerator, "objective_denominator": SCALE ** 4,
            "cryptographic_zero_knowledge": False,
            "scope": "local_exact_witness_arithmetic_not_private_exchange_or_global_optimum"}
