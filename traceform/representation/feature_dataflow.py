"""Static dataflow analysis of the normalizer's 128 continuous outputs.

For each output the analysis reports which observation fields can influence its
value (directly, through previous-frame state, or only through branch
conditions). It is a may-dependence over-approximation of the source text, not
runtime evidence: it cannot say which branch ran on a given frame, and a field
appearing here does not mean the field was measured.
"""
from __future__ import annotations

import ast
import hashlib
from typing import Dict, FrozenSet, List, Optional, Set, Tuple

EXTRACTION_FUNCTION = 'extract_raw_features'
OBSERVATION_ROOT = 'obs'
TIMESTAMP_PATH = 'timestamp_utc'
MUTATING_METHODS = {'append', 'appendleft', 'extend', 'extendleft', 'add', 'insert', 'update', 'setdefault'}

Atoms = FrozenSet[str]
Value = Tuple[Optional[str], Atoms, Atoms]  # (observation container path, data atoms, control atoms)
EMPTY: Atoms = frozenset()


def _obs(path: str) -> str:
    return f'obs:{path}'


class _Analyzer:
    def __init__(self, function: ast.FunctionDef):
        self.function = function
        self.data: Dict[str, Set[str]] = {}
        self.control: Dict[str, Set[str]] = {}
        self.paths: Dict[str, Optional[str]] = {}
        self.state_data: Dict[str, Set[str]] = {}
        self.control_stack: List[Atoms] = []
        self.written_state = self._written_state(function)
        self.data[OBSERVATION_ROOT] = set()  # the observation frame is the root container
        self.control[OBSERVATION_ROOT] = set()
        self.paths[OBSERVATION_ROOT] = ''

    @staticmethod
    def _written_state(function: ast.FunctionDef) -> Set[str]:
        written: Set[str] = set()

        def self_attr(node) -> Optional[str]:
            while isinstance(node, ast.Subscript):
                node = node.value
            if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == 'self':
                return node.attr
            return None

        for node in ast.walk(function):
            targets = []
            if isinstance(node, ast.Assign):
                targets = node.targets
            elif isinstance(node, (ast.AugAssign, ast.AnnAssign)):
                targets = [node.target]
            for target in targets:
                for sub in ast.walk(target):
                    name = self_attr(sub)
                    if name:
                        written.add(name)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                    and node.func.attr in MUTATING_METHODS:
                name = self_attr(node.func.value)
                if name:
                    written.add(name)
        return written

    # ----- expressions -------------------------------------------------
    def value(self, node) -> Value:
        if node is None or isinstance(node, ast.Constant):
            return None, EMPTY, EMPTY
        if isinstance(node, ast.Name):
            return self._name(node.id)
        if isinstance(node, ast.Attribute):
            return self._attribute(node)
        if isinstance(node, ast.Subscript):
            return self._subscript(node)
        if isinstance(node, ast.Call):
            return self._call(node)
        if isinstance(node, ast.IfExp):
            _, test_data, test_control = self.value(node.test)
            _, body_data, body_control = self.value(node.body)
            _, other_data, other_control = self.value(node.orelse)
            return None, body_data | other_data, body_control | other_control | test_data | test_control
        if isinstance(node, (ast.ListComp, ast.GeneratorExp, ast.SetComp, ast.DictComp)):
            return self._comprehension(node)
        if isinstance(node, ast.Lambda):
            return None, self.value(node.body)[1], EMPTY
        return self._union(ast.iter_child_nodes(node))

    def _union(self, nodes) -> Value:
        data: Set[str] = set()
        control: Set[str] = set()
        for child in nodes:
            if isinstance(child, (ast.expr_context, ast.operator, ast.cmpop, ast.boolop, ast.unaryop)):
                continue
            _, child_data, child_control = self.value(child)
            data |= child_data
            control |= child_control
        return None, frozenset(data), frozenset(control)

    def _name(self, name: str) -> Value:
        if name not in self.data:
            return None, EMPTY, EMPTY
        path = self.paths.get(name)
        data = frozenset(self.data[name])
        if path and not data:
            data = frozenset({_obs(path)})
        return path, data, frozenset(self.control.get(name, ()))

    def _attribute(self, node: ast.Attribute) -> Value:
        if isinstance(node.value, ast.Name) and node.value.id == 'self':
            kind = 'state' if node.attr in self.written_state else 'param'
            return None, frozenset({f'{kind}:{node.attr}'}), EMPTY
        return self._union([node.value])

    def _child_path(self, base: Value, key) -> Value:
        base_path, base_data, base_control = base
        if base_path is None or not isinstance(key, ast.Constant) or not isinstance(key.value, str):
            return None, base_data, base_control
        path = f'{base_path}.{key.value}' if base_path else key.value
        return path, frozenset({_obs(path)}), base_control

    def _subscript(self, node: ast.Subscript) -> Value:
        base = self.value(node.value)
        key = node.slice
        if isinstance(key, ast.Constant) and isinstance(key.value, str):
            return self._child_path(base, key)
        _, key_data, key_control = self.value(key)
        return base[0], base[1] | key_data, base[2] | key_control

    def _call(self, node: ast.Call) -> Value:
        func = node.func
        if isinstance(func, ast.Attribute) and func.attr == 'get' and node.args:
            base = self.value(func.value)
            default = self.value(node.args[1]) if len(node.args) > 1 else (None, EMPTY, EMPTY)
            path, data, control = self._child_path(base, node.args[0])
            if base[0] is None:
                data = base[1] | self.value(node.args[0])[1]
            return path, data | default[1], control | default[2]
        data: Set[str] = set()
        control: Set[str] = set()
        parts = list(node.args) + [keyword.value for keyword in node.keywords]
        if isinstance(func, ast.Attribute):
            parts.append(func.value)
        for part in parts:
            _, part_data, part_control = self.value(part)
            data |= part_data
            control |= part_control
        return None, frozenset(data), frozenset(control)

    def _comprehension(self, node) -> Value:
        for generator in node.generators:
            _, iter_data, iter_control = self.value(generator.iter)
            for name in self._target_names(generator.target):
                self._bind(name, None, iter_data, iter_control)
        elements = [node.key, node.value] if isinstance(node, ast.DictComp) else [node.elt]
        conditions = [condition for generator in node.generators for condition in generator.ifs]
        data: Set[str] = set()
        control: Set[str] = set()
        for element in elements:
            _, element_data, element_control = self.value(element)
            data |= element_data
            control |= element_control
        for condition in conditions:
            _, test_data, test_control = self.value(condition)
            control |= test_data | test_control
        return None, frozenset(data), frozenset(control)

    # ----- statements --------------------------------------------------
    @staticmethod
    def _target_names(target) -> List[str]:
        return [n.id for n in ast.walk(target) if isinstance(n, ast.Name)]

    def _bind(self, name: str, path: Optional[str], data: Atoms, control: Atoms) -> None:
        first = name not in self.data
        self.data.setdefault(name, set()).update(data)
        self.control.setdefault(name, set()).update(control)
        for atoms in self.control_stack:
            self.control[name].update(atoms)
        if first:
            self.paths[name] = path
        elif self.paths.get(name) != path:
            self.paths[name] = None

    def _store_state(self, attr: str, data: Atoms) -> None:
        self.state_data.setdefault(attr, set()).update(a for a in data if not a.startswith('param:'))

    def _assign(self, target, value: Value) -> None:
        path, data, control = value
        if isinstance(target, ast.Name):
            self._bind(target.id, path, data, control)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for element in target.elts:
                self._assign(element, (None, data, control))
        elif isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) and target.value.id == 'self':
            self._store_state(target.attr, data)
        elif isinstance(target, ast.Subscript):
            base = target.value
            if isinstance(base, ast.Name):
                self._bind(base.id, None, data, control)
            elif isinstance(base, ast.Attribute) and isinstance(base.value, ast.Name) and base.value.id == 'self':
                self._store_state(base.attr, data)

    def run_block(self, body) -> None:
        for statement in body:
            self.run_statement(statement)

    def run_statement(self, node) -> None:
        if isinstance(node, ast.Assign):
            value = self.value(node.value)
            for target in node.targets:
                self._assign(target, value)
        elif isinstance(node, ast.AnnAssign):
            if node.value is not None:
                self._assign(node.target, self.value(node.value))
        elif isinstance(node, ast.AugAssign):
            current = self.value(node.target)
            added = self.value(node.value)
            self._assign(node.target, (None, current[1] | added[1], current[2] | added[2]))
        elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Call):
            self._mutation(node.value)
        elif isinstance(node, ast.If):
            _, test_data, test_control = self.value(node.test)
            self.control_stack.append(test_data | test_control)
            self.run_block(node.body)
            self.run_block(node.orelse)
            self.control_stack.pop()
        elif isinstance(node, (ast.For, ast.AsyncFor)):
            _, iter_data, iter_control = self.value(node.iter)
            self.control_stack.append(iter_data | iter_control)
            for name in self._target_names(node.target):
                self._bind(name, None, iter_data, iter_control)
            self.run_block(node.body)
            self.run_block(node.orelse)
            self.control_stack.pop()
        elif isinstance(node, ast.While):
            _, test_data, test_control = self.value(node.test)
            self.control_stack.append(test_data | test_control)
            self.run_block(node.body)
            self.run_block(node.orelse)
            self.control_stack.pop()
        elif isinstance(node, (ast.With, ast.AsyncWith)):
            self.run_block(node.body)
        elif isinstance(node, ast.Try):
            self.run_block(node.body)
            for handler in node.handlers:
                self.run_block(handler.body)
            self.run_block(node.orelse)
            self.run_block(node.finalbody)

    def _mutation(self, call: ast.Call) -> None:
        func = call.func
        if not (isinstance(func, ast.Attribute) and func.attr in MUTATING_METHODS):
            return
        data: Set[str] = set()
        control: Set[str] = set()
        for argument in list(call.args) + [keyword.value for keyword in call.keywords]:
            _, argument_data, argument_control = self.value(argument)
            data |= argument_data
            control |= argument_control
        base = func.value
        if isinstance(base, ast.Name):
            self._bind(base.id, None, frozenset(data), frozenset(control))
        elif isinstance(base, ast.Attribute) and isinstance(base.value, ast.Name) and base.value.id == 'self':
            self._store_state(base.attr, frozenset(data))

    # ----- results -----------------------------------------------------
    def expand_history(self, atoms: Atoms) -> Tuple[Set[str], Set[str]]:
        """Return (state attributes, observation paths) reachable through history."""
        attrs: Set[str] = set()
        paths: Set[str] = set()
        pending = [a[len('state:'):] for a in atoms if a.startswith('state:')]
        while pending:
            attr = pending.pop()
            if attr in attrs:
                continue
            attrs.add(attr)
            for atom in self.state_data.get(attr, ()):
                if atom.startswith('state:'):
                    pending.append(atom[len('state:'):])
                elif atom.startswith('obs:'):
                    paths.add(atom[len('obs:'):])
        return attrs, paths


def _find_function(tree: ast.AST) -> ast.FunctionDef:
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == EXTRACTION_FUNCTION:
            return node
    raise ValueError(f'{EXTRACTION_FUNCTION} not found')


def _output_mapping(function: ast.FunctionDef) -> ast.Dict:
    returned = None
    for node in ast.walk(function):
        if isinstance(node, ast.Return) and isinstance(node.value, ast.Tuple) and node.value.elts:
            returned = node.value.elts[0]
    if isinstance(returned, ast.Dict):
        return returned
    if isinstance(returned, ast.Name):
        for node in ast.walk(function):
            if isinstance(node, (ast.Assign, ast.AnnAssign)) and isinstance(node.value, ast.Dict):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                if any(isinstance(t, ast.Name) and t.id == returned.id for t in targets):
                    return node.value
    raise ValueError('continuous output mapping not found')


def _name_tokens(text: str) -> Set[str]:
    return {token for token in text.lower().split('_') if len(token) >= 4}


def _condition_names_output(feature: str, condition_paths: List[str]) -> bool:
    """Heuristic: a tested field whose name overlaps the feature name is likely its own indicator."""
    feature_tokens = _name_tokens(feature)
    return any(_name_tokens(path.split('.')[-1]) & feature_tokens for path in condition_paths)


def _classify(inputs: List[str], history_inputs: List[str], history_state: List[str],
              control_inputs: List[str]) -> str:
    seen = set(inputs) | set(history_inputs)
    if not seen:
        return 'condition_selected' if control_inputs else 'no_observation_input'
    if seen <= {TIMESTAMP_PATH}:
        return 'time_only'
    return 'history_derived' if history_state else 'observation_derived'


def analyze_source(source: str) -> Dict[str, object]:
    tree = ast.parse(source)
    function = _find_function(tree)
    mapping = _output_mapping(function)
    analyzer = _Analyzer(function)
    for _ in range(2):  # second pass settles variables read before their loop-carried assignment
        analyzer.run_block(function.body)
    features = []
    for index, (key, expression) in enumerate(zip(mapping.keys, mapping.values)):
        if not (isinstance(key, ast.Constant) and isinstance(key.value, str)):
            raise ValueError('non-literal output name')
        _, data, control = analyzer.value(expression)
        inputs = sorted(a[len('obs:'):] for a in data if a.startswith('obs:'))
        state = {a[len('state:'):] for a in data if a.startswith('state:')}
        history_attrs, history_paths = analyzer.expand_history(data)
        control_state_attrs, control_state_paths = analyzer.expand_history(control)
        control_paths = {a[len('obs:'):] for a in control if a.startswith('obs:')} | control_state_paths
        control_only = sorted(control_paths - set(inputs) - history_paths)
        history_state = sorted(state)
        history_inputs = sorted(history_paths)
        features.append({
            'index': index,
            'feature': key.value,
            'observation_inputs': inputs,
            'history_state': history_state,
            'history_observation_inputs': history_inputs,
            'control_observation_inputs': control_only,
            'condition_field_named_in_output': _condition_names_output(key.value, control_only),
            'parameters': sorted(a[len('param:'):] for a in data | control if a.startswith('param:')),
            'modalities': sorted({p.split('.')[0] for p in inputs + history_inputs}),
            'uses_history': bool(history_state),
            'input_class': _classify(inputs, history_inputs, history_state, control_only),
            'output_expression': ast.unparse(expression),
            'output_line': expression.lineno,
        })
    return {
        'scope': 'static_may_dependence_over_approximation_not_runtime_branch_or_measurement_evidence',
        'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
        'function': EXTRACTION_FUNCTION,
        'feature_count': len(features),
        'features': features,
        'limitations': [
            'dependencies are a may-over-approximation: a listed field does not mean it affected a given frame',
            'a field being present in an observation does not establish that it was measured rather than defaulted',
            'history state is analyzed by name; warm-up length and eviction are not modeled',
            'condition_selected outputs take values chosen by tests alone; whether the tested field is the quantity '
            'itself or a stand-in for another quantity is only hinted by condition_field_named_in_output',
        ],
    }


def backward_slice(source: str, features) -> List[int]:
    """Source line numbers of the statements that can influence the named outputs.

    Follows local variables and ``self`` state across statements (including
    buffers mutated by ``append``) and adds the lines of enclosing branch tests.
    The result is a reading aid for porting, not an executable program.
    """
    tree = ast.parse(source)
    function = _find_function(tree)
    mapping = _output_mapping(function)
    expressions = {key.value: value for key, value in zip(mapping.keys, mapping.values)
                   if isinstance(key, ast.Constant)}
    wanted = [features] if isinstance(features, str) else list(features)
    unknown = [name for name in wanted if name not in expressions]
    if unknown:
        raise ValueError(f'unknown feature: {unknown[0]}')

    parents: Dict[ast.AST, ast.AST] = {}
    for parent in ast.walk(function):
        for child in ast.iter_child_nodes(parent):
            parents[child] = parent

    def reads(node) -> Set[str]:
        found: Set[str] = set()
        for sub in ast.walk(node):
            if isinstance(sub, ast.Name) and isinstance(sub.ctx, ast.Load):
                found.add(sub.id)
            elif isinstance(sub, ast.Attribute) and isinstance(sub.value, ast.Name) and sub.value.id == 'self':
                found.add(f'self.{sub.attr}')
        return found

    def written(statement) -> Set[str]:
        names: Set[str] = set()
        targets = []
        if isinstance(statement, ast.Assign):
            targets = statement.targets
        elif isinstance(statement, (ast.AugAssign, ast.AnnAssign)):
            targets = [statement.target]
        for target in targets:
            for sub in ast.walk(target):
                if isinstance(sub, ast.Name):
                    names.add(sub.id)
                elif isinstance(sub, ast.Attribute) and isinstance(sub.value, ast.Name) and sub.value.id == 'self':
                    names.add(f'self.{sub.attr}')
        if isinstance(statement, ast.Expr) and isinstance(statement.value, ast.Call):
            func = statement.value.func
            if isinstance(func, ast.Attribute) and func.attr in MUTATING_METHODS:
                base = func.value
                while isinstance(base, ast.Subscript):
                    base = base.value
                if isinstance(base, ast.Name):
                    names.add(base.id)
                elif isinstance(base, ast.Attribute) and isinstance(base.value, ast.Name) and base.value.id == 'self':
                    names.add(f'self.{base.attr}')
        return names

    definers: Dict[str, List[ast.AST]] = {}
    for node in ast.walk(function):
        if isinstance(node, (ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Expr)):
            for name in written(node):
                definers.setdefault(name, []).append(node)

    def control_tests(statement):
        parent = parents.get(statement)
        while parent is not None and parent is not function:
            if isinstance(parent, (ast.If, ast.While)):
                yield parent.test
            elif isinstance(parent, (ast.For, ast.AsyncFor)):
                yield parent.iter
            parent = parents.get(parent)

    lines: Set[int] = set()
    seen: Set[str] = set()
    pending: List[str] = []
    for name in wanted:
        lines.update(range(expressions[name].lineno, expressions[name].end_lineno + 1))
        pending.extend(reads(expressions[name]))
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        for statement in definers.get(name, []):
            lines.update(range(statement.lineno, statement.end_lineno + 1))
            pending.extend(reads(statement))
            for test in control_tests(statement):
                lines.update(range(test.lineno, test.end_lineno + 1))
                pending.extend(reads(test))
    return sorted(lines)
