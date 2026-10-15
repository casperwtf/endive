package run.endive.compiler.internal;

import static run.endive.wasm.types.Instruction.EMPTY_OPERANDS;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import run.endive.wasm.InvalidException;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.TypeSection;
import run.endive.wasm.types.ValType;

final class TypeStack {

    public static final Instruction FUNCTION_SCOPE =
            new Instruction(-1, OpCode.NOP, EMPTY_OPERANDS);

    private final Deque<Deque<ValType>> types = new ArrayDeque<>();
    private final Deque<Deque<ValType>> restore = new ArrayDeque<>();
    private final Map<Instruction, Integer> scopes = new HashMap<>();
    private final TypeSection typeSection;
    private int low = Integer.MAX_VALUE;

    public TypeStack(TypeSection typeSection) {
        this.typeSection = typeSection;
        this.types.push(new ArrayDeque<>());
    }

    public ValType peek() {
        return types().getFirst();
    }

    public void push(ValType type) {
        types().push(type);
    }

    /** Starts tracking the lowest stack height reached from now on. */
    public void resetLow() {
        low = types().size();
    }

    /** The lowest stack height reached since {@link #resetLow()}. */
    public int low() {
        return low;
    }

    /** Records that the top {@code count} values are used, as by a branch carrying them. */
    public void touch(int count) {
        low = Math.min(low, types().size() - count);
    }

    public void pop(ValType expected) {
        var actual = types().pop();
        low = Math.min(low, types().size());
        if (!ValType.matches(actual, expected, typeSection)) {
            throw new IllegalArgumentException("Expected type " + expected + " <> " + actual);
        }
    }

    public void popRef() {
        var actual = types().pop();
        low = Math.min(low, types().size());
        if (!actual.isReference()) {
            throw new IllegalArgumentException("Expected reference type <> " + actual);
        }
    }

    public void pushTypes() {
        types.push(new ArrayDeque<>(types()));
    }

    public void popTypes() {
        types.pop();
    }

    public void enterScope(Instruction scope, FunctionType scopeType) {
        scopes.put(scope, types().size());
        // the block takes its parameters from the enclosing stack
        low = Math.min(low, types().size() - scopeType.params().size());

        // restored stack when exiting "polymorphic" blocks after unconditional control transfer
        Deque<ValType> stack = new ArrayDeque<>(types());
        for (int i = 0; i < scopeType.params().size(); i++) {
            stack.pop();
        }
        for (ValType type : scopeType.returns()) {
            stack.push(type);
        }
        restore.push(stack);
    }

    public void exitScope(Instruction scope) {
        scopes.remove(scope);
        restore.pop();
    }

    public void scopeRestore() {
        types.pop();
        types.push(restore.getFirst());
    }

    public Integer scopeStackSize(Instruction scope) {
        return scopes.get(scope);
    }

    public Deque<ValType> types() {
        return types.getFirst();
    }

    public void verifyEmpty() {
        if (types.size() != 1) {
            throw new InvalidException("Bad types stack: " + types);
        }
        if (!types().isEmpty()) {
            throw new InvalidException("Types not empty: " + types());
        }
    }
}
