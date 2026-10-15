package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.internal.ClassLoadingCollector;
import run.endive.compiler.internal.Compiler;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.tools.wasm.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Splits the functions of method_split.wat into segments at several budgets, and checks every
 * split returns, throws and traps exactly as the interpreter does.
 */
public class MethodSplitTest {

    private static final WasmModule MODULE =
            Parser.parse(
                    Wat2Wasm.parse(MethodSplitTest.class.getResourceAsStream("method_split.wat")));

    private static final int[] BUDGETS = {1, 2, 3, 5, 8, 13, 30, 100};

    private static final long[] INPUTS = {0, 1, 2, 3, 5, 6, 7, 11, 40, 42, 43, 45, 63, 64, 99, 300};

    private static final long[] FLOAT_BITS = {
        0, 1, 0x7fa00001L, 0x7fc00000L, 0xffc00001L, 0x3f800000L, 0x80000000L
    };

    private static final String[] EXPORTS = {
        "dispatch",
        "throwLocals",
        "catchOut",
        "exnref",
        "multi",
        "multiSum",
        "carry",
        "funcrefDefault",
        "branches",
        "unreachableTail",
        "chain"
    };

    @Test
    public void splitFunctionsBehaveLikeTheInterpreter() {
        Instance interpreted =
                Instance.builder(MODULE).withMachineFactory(InterpreterMachine::new).build();
        Map<String, List<String>> expected = run(interpreted);

        Instance whole = Instance.builder(MODULE).withMachineFactory(compiled(0, 0)).build();
        assertEquals(expected, run(whole), "compiled without splitting");

        for (int budget : BUDGETS) {
            var result =
                    Compiler.builder(MODULE)
                            .withMethodSplitLimit(1)
                            .withMethodSplitBudget(budget)
                            .build()
                            .compile();
            assertTrue(
                    segmentMethods(result.collector().classBytes()) > 0,
                    "no function was split at budget " + budget);

            Instance split =
                    Instance.builder(MODULE).withMachineFactory(compiled(1, budget)).build();
            assertEquals(expected, run(split), "split at budget " + budget);
        }
    }

    private static Function<Instance, Machine> compiled(int limit, int budget) {
        var result =
                Compiler.builder(MODULE)
                        .withMethodSplitLimit(limit)
                        .withMethodSplitBudget(budget)
                        .build()
                        .compile();
        return ((ClassLoadingCollector) result.collector()).machineFactory();
    }

    private static Map<String, List<String>> run(Instance instance) {
        Map<String, List<String>> results = new TreeMap<>();
        for (String name : EXPORTS) {
            ExportFunction function = instance.export(name);
            List<String> outcomes = new ArrayList<>();
            for (long input : INPUTS) {
                outcomes.add(call(function, input));
            }
            results.put(name, outcomes);
        }
        ExportFunction floatBits = instance.export("floatBits");
        List<String> outcomes = new ArrayList<>();
        for (long bits : FLOAT_BITS) {
            outcomes.add(call(floatBits, bits));
        }
        results.put("floatBits", outcomes);
        return results;
    }

    private static String call(ExportFunction function, long input) {
        try {
            return Arrays.toString(function.apply(input));
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static int segmentMethods(Map<String, byte[]> classBytes) {
        int[] count = new int[1];
        for (byte[] bytes : classBytes.values()) {
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    if (name.contains("$")) {
                                        count[0]++;
                                    }
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE);
        }
        return count[0];
    }

    @Test
    public void splitsAFunctionOverTheLimit() {
        var result = Compiler.builder(MODULE).withMethodSplitLimit(300).build().compile();
        assertTrue(segmentMethods(result.collector().classBytes()) > 0);
    }
}
