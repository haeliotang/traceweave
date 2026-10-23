"""Exact integer reference for bounded private transport descent.

This module sees plaintext models. It is an oracle for a future circuit,
not a private protocol or a global-optimum certificate.
"""
from traceform.dynamics.alignment_statement import SCALE, validate_alignment_witness


def northwest_transport(first_mass, second_mass):
    for masses in (first_mass, second_mass):
        if not 1 <= len(masses) <= 16 or any(type(v) is not int or not 0 <= v <= SCALE for v in masses) or sum(masses) != SCALE:
            raise ValueError('bounded integer masses summing to scale required')
    rows, columns = list(first_mass), list(second_mass)
    coupling = []
    for row in range(len(rows)):
        values = []
        for column in range(len(columns)):
            value = min(rows[row], columns[column])
            values.append(value)
            rows[row] -= value
            columns[column] -= value
        coupling.append(values)
    return coupling


def cycle_objective(first, second, coupling, objective, i, j, k, l, delta):
    """Evaluate the exact four-cell quadratic, using fixed transport marginals."""
    gradient = sum((first[i][a] - first[j][a]) * coupling[a][b]
                   * (second[b][k] - second[b][l])
                   for a in range(len(first)) for b in range(len(second)))
    return objective - 4 * gradient * delta - 8 * first[i][j] * second[k][l] * delta ** 2


def optimize_integer_transport(first, second, first_mass, second_mass):
    coupling = northwest_transport(first_mass, second_mass)
    initial = validate_alignment_witness(first, second, coupling, first_mass, second_mass)['objective_numerator']
    objective = initial
    # For a metric four-cycle the quadratic coefficient is -8*Aij*Bkl <= 0.
    # Its minimum on the feasible delta interval is therefore at an endpoint.
    for i in range(len(first)):
        for j in range(i + 1, len(first)):
            for k in range(len(second)):
                for l in range(k + 1, len(second)):
                    lower = -min(coupling[i][k], coupling[j][l])
                    upper = min(coupling[i][l], coupling[j][k])
                    best_delta, best_value = 0, objective
                    for delta in (lower, upper):
                        value = cycle_objective(first, second, coupling, objective, i, j, k, l, delta)
                        if value < best_value:
                            best_delta, best_value = delta, value
                    coupling[i][k] += best_delta
                    coupling[j][l] += best_delta
                    coupling[i][l] -= best_delta
                    coupling[j][k] -= best_delta
                    objective = best_value
    checked = validate_alignment_witness(first, second, coupling, first_mass, second_mass)
    if checked['objective_numerator'] != objective:
        raise ValueError('cycle objective arithmetic mismatch')
    return {'coupling': coupling, 'objective_numerator': objective,
            'initial_objective_numerator': initial, 'sweeps': 1,
            'scope': 'exact_single_cycle_sweep_not_global_optimum_or_private_protocol'}


def optimize_uniform_permutation(first, second, first_mass, second_mass):
    """Exact swap descent for equal-cardinality, exactly uniform integer masses.

    This restricts couplings to scaled permutations. It does not replace the
    general transport descent or certify a stationary/global optimum.
    """
    nodes = len(first)
    if (not 1 <= nodes <= 16 or len(second) != nodes or SCALE % nodes
            or first_mass != [SCALE // nodes] * nodes or second_mass != first_mass):
        raise ValueError('equal-cardinality exactly uniform integer masses required')
    mass = SCALE // nodes
    permutation = list(range(nodes))
    coupling = [[mass if i == j else 0 for j in range(nodes)] for i in range(nodes)]
    initial = validate_alignment_witness(first, second, coupling, first_mass, second_mass)['objective_numerator']
    objective = initial
    aligned = [row[:] for row in second]
    for i in range(nodes):
        for j in range(i + 1, nodes):
            change = 4 * mass ** 2 * sum((first[i][r] - first[j][r]) * (aligned[i][r] - aligned[j][r])
                                         for r in range(nodes) if r != i and r != j)
            if change < 0:
                objective += change
                permutation[i], permutation[j] = permutation[j], permutation[i]
                aligned[i], aligned[j] = aligned[j], aligned[i]
                for row in aligned:
                    row[i], row[j] = row[j], row[i]
    coupling = [[mass if j == permutation[i] else 0 for j in range(nodes)] for i in range(nodes)]
    checked = validate_alignment_witness(first, second, coupling, first_mass, second_mass)
    if checked['objective_numerator'] != objective:
        raise ValueError('permutation objective arithmetic mismatch')
    return {'coupling': coupling, 'permutation': permutation, 'objective_numerator': objective,
            'initial_objective_numerator': initial, 'sweeps': 1,
            'scope': 'exact_uniform_permutation_sweep_not_general_transport_optimum_or_private_protocol'}


def optimize_quantized_uniform_permutation(first, second, first_mass, second_mass, search_distance_shift=12):
    """Search with eight/six-bit distances, then gate at full precision.

    Rounding may change swap choices. The final original-distance loss cannot
    exceed the identity loss; this does not preserve the exact search path.
    """
    nodes = len(first)
    if search_distance_shift not in (12, 14):
        raise ValueError('only eight-bit or six-bit private search contracts supported')
    if (not 1 <= nodes <= 16 or len(second) != nodes or SCALE % nodes
            or first_mass != [SCALE // nodes] * nodes or second_mass != first_mass):
        raise ValueError('equal-cardinality exactly uniform integer masses required')
    mass = SCALE // nodes
    identity = [[mass if i == j else 0 for j in range(nodes)] for i in range(nodes)]
    initial = validate_alignment_witness(first, second, identity, first_mass, second_mass)['objective_numerator']
    coarse_first = [[value >> search_distance_shift for value in row] for row in first]
    aligned = [[value >> search_distance_shift for value in row] for row in second]
    permutation = list(range(nodes))
    for i in range(nodes):
        for j in range(i + 1, nodes):
            gradient = sum((coarse_first[i][r] - coarse_first[j][r]) * (aligned[i][r] - aligned[j][r])
                           for r in range(nodes) if r != i and r != j)
            if gradient < 0:
                permutation[i], permutation[j] = permutation[j], permutation[i]
                aligned[i], aligned[j] = aligned[j], aligned[i]
                for row in aligned:
                    row[i], row[j] = row[j], row[i]
    coupling = [[mass if j == permutation[i] else 0 for j in range(nodes)] for i in range(nodes)]
    objective = validate_alignment_witness(first, second, coupling, first_mass, second_mass)['objective_numerator']
    rejected = objective > initial
    if rejected:
        coupling, permutation, objective = identity, list(range(nodes)), initial
    return {'coupling': coupling, 'permutation': permutation, 'objective_numerator': objective,
            'initial_objective_numerator': initial, 'sweeps': 1, 'search_distance_shift': search_distance_shift,
            'full_precision_candidate_rejected': rejected,
            'scope': 'quantized_permutation_search_with_exact_final_loss_not_exact_search_or_global_optimum_or_private_protocol'}
