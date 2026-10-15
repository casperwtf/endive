package run.endive.compiler.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import run.endive.wasm.types.AnnotatedInstruction;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.ValType;

/**
 * Splits a WASM function whose JVM bytecode would be too large for HotSpot to JIT compile
 * (methods over {@code HugeMethodLimit}, 8000 bytes, are never compiled) into a root method and
 * several segment methods.
 *
 * <p>A segment is a run of whole instructions and constructs inside one block body that leaves the
 * WASM operand stack at the height it found it and never pops below that height: the values under
 * it stay on the caller's JVM stack, and no stack value crosses the call. The root keeps the
 * control skeleton (the blocks, loops and dispatch tables around the segments) and every WASM local
 * of a split function lives in a {@code long[]} frame (and an {@code Object[]} for GC references),
 * which the root reads and writes directly. A segment loads the locals it uses from the frame on
 * entry, writes back the ones it assigns on every exit (including a {@code WasmException}
 * unwinding through it), and returns an exit code: {@code 0} when it falls off its end, otherwise
 * the index (plus one) of the branch target outside the segment it took, or of a return, with the
 * values carried by that branch stored after the locals in the frame. The root then
 * performs that branch.
 */
final class MethodSplit {

    static final int RETURN_LABEL = -1;

    /** A control transfer leaving a segment. */
    static final class Exit {
        final int label;
        final List<ValType> types;

        Exit(int label, List<ValType> types) {
            this.label = label;
            this.types = types;
        }
    }

    static final class Segment {
        final int index;
        final int start;
        final int end;
        final int bodyDepth;
        final boolean fallsThrough;
        final int[] loaded;
        final int[] written;
        final List<Exit> exits = new ArrayList<>();
        WasmAnalyzer.AnalysisResult analysis;

        Segment(
                int index,
                int start,
                int end,
                int bodyDepth,
                boolean fallsThrough,
                int[] loaded,
                int[] written) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.bodyDepth = bodyDepth;
            this.fallsThrough = fallsThrough;
            this.loaded = loaded;
            this.written = written;
        }

        /** Returns the exit code (1-based) for a branch to {@code label}, adding it if new. */
        int exitCode(int label, List<ValType> types) {
            for (int i = 0; i < exits.size(); i++) {
                if (exits.get(i).label == label) {
                    return i + 1;
                }
            }
            exits.add(new Exit(label, types));
            return exits.size();
        }

        boolean isInside(long label, int instructionCount) {
            if (label >= instructionCount) {
                return true;
            }
            return start < label && label < end;
        }
    }

    private final List<Segment> segments;
    private final Segment[] byStart;
    WasmAnalyzer.AnalysisResult rootAnalysis;
    private final int localCount;

    private MethodSplit(List<Segment> segments, int instructionCount, int localCount) {
        this.segments = segments;
        this.byStart = new Segment[instructionCount];
        for (Segment segment : segments) {
            byStart[segment.start] = segment;
        }
        this.localCount = localCount;
    }

    List<Segment> segments() {
        return segments;
    }

    Segment segmentAt(int index) {
        return byStart[index];
    }

    int localCount() {
        return localCount;
    }

    /** The frame slots after the locals, holding the values carried by a segment's exit. */
    int resultSlots() {
        int max = 0;
        for (Segment segment : segments) {
            for (Exit exit : segment.exits) {
                max = Math.max(max, exit.types.size());
            }
        }
        return max;
    }

    int frameSize() {
        return localCount + resultSlots();
    }

    // ===== planning =====

    private static final int DISPATCH_TABLE_SIZE = 16;

    /**
     * Plans the segments of a function body.
     *
     * @param profile the WASM operand stack around each instruction
     * @param budget the largest weight of one segment
     * @param minWeight the smallest weight worth moving into a segment
     * @return the plan, or {@code null} when nothing can be split off
     */
    static MethodSplit plan(
            FunctionType type,
            FunctionBody body,
            WasmAnalyzer.StackProfile profile,
            int budget,
            int minWeight,
            Function<Instruction, FunctionType> blockTypes) {
        var planner = new Planner(body, profile, budget, minWeight, blockTypes);
        int last = body.instructions().size() - 1;
        planner.planBody(0, last, 0);
        if (planner.segments.isEmpty()) {
            return null;
        }
        int localCount = type.params().size() + body.localTypes().size();
        return new MethodSplit(planner.segments, body.instructions().size(), localCount);
    }

    static int weightOf(Instruction ins) {
        if (ins.opcode() == OpCode.BR_TABLE) {
            return 1 + ins.operandCount() / 4;
        }
        return 1;
    }

    static int totalWeight(FunctionBody body) {
        int total = 0;
        for (var ins : body.instructions()) {
            total += weightOf(ins);
        }
        return total;
    }

    private static final class Planner {
        private final List<AnnotatedInstruction> instructions;
        private final int[] heights;
        private final int[] lows;
        private final int budget;
        private final int minWeight;
        private final Function<Instruction, FunctionType> blockTypes;
        private final int[] matchingEnd;
        private final int[] elseOf;
        private final long[] weightBefore;
        private final List<Segment> segments = new ArrayList<>();

        Planner(
                FunctionBody body,
                WasmAnalyzer.StackProfile profile,
                int budget,
                int minWeight,
                Function<Instruction, FunctionType> blockTypes) {
            this.instructions = body.instructions();
            this.heights = profile.heights;
            this.lows = profile.lows;
            this.budget = budget;
            this.minWeight = minWeight;
            this.blockTypes = blockTypes;

            int count = instructions.size();
            this.matchingEnd = new int[count];
            this.elseOf = new int[count];
            this.weightBefore = new long[count + 1];
            Deque<Integer> open = new ArrayDeque<>();
            for (int i = 0; i < count; i++) {
                var ins = instructions.get(i);
                elseOf[i] = -1;
                matchingEnd[i] = -1;
                weightBefore[i + 1] = weightBefore[i] + weightOf(ins);
                switch (ins.opcode()) {
                    case BLOCK:
                    case LOOP:
                    case IF:
                    case TRY_TABLE:
                        open.push(i);
                        break;
                    case ELSE:
                        if (!open.isEmpty()) {
                            elseOf[open.peek()] = i;
                        }
                        break;
                    case END:
                        if (!open.isEmpty()) {
                            matchingEnd[open.pop()] = i;
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        private boolean opensConstruct(int index) {
            return matchingEnd[index] >= 0;
        }

        private int next(int index) {
            if (opensConstruct(index)) {
                return matchingEnd[index] + 1;
            }
            return index + 1;
        }

        private long weight(int start, int end) {
            return weightBefore[end] - weightBefore[start];
        }

        /**
         * Splits the body {@code [start, end)} into segments. A segment is a run of the body's
         * elements (instructions, and whole blocks, loops, ifs and try_tables) that leaves the
         * stack at the height it found it and never pops below that height, so the values under
         * it stay in the caller.
         */
        void planBody(int start, int end, int depth) {
            Deque<int[]> bodies = new ArrayDeque<>();
            bodies.push(new int[] {start, end, depth});
            while (!bodies.isEmpty()) {
                int[] body = bodies.pop();
                planOneBody(body[0], body[1], body[2], bodies);
            }
        }

        /** Plans one body; the bodies of the constructs it leaves in the root go on the work list. */
        private void planOneBody(int start, int end, int depth, Deque<int[]> bodies) {
            if (start >= end) {
                return;
            }
            List<Integer> positions = new ArrayList<>();
            for (int index = start; index < end; index = next(index)) {
                positions.add(index);
            }
            positions.add(end);

            int at = 0;
            while (at < positions.size() - 1) {
                int segmentEnd = segmentEnd(positions, at);
                if (segmentEnd > at) {
                    flush(positions.get(at), positions.get(segmentEnd), depth);
                    at = segmentEnd;
                    continue;
                }
                int element = positions.get(at);
                if (opensConstruct(element)) {
                    addBodies(element, bodies);
                }
                at++;
            }
        }

        /** The last position a segment starting at {@code positions[at]} can end at, or -1. */
        private int segmentEnd(List<Integer> positions, int at) {
            int start = positions.get(at);
            int height = heights[start];
            if (height < 0) {
                return -1;
            }
            int best = -1;
            int low = Integer.MAX_VALUE;
            for (int next = at + 1; next < positions.size(); next++) {
                int from = positions.get(next - 1);
                int to = positions.get(next);
                if (weight(start, to) > budget || !isPackable(from, to)) {
                    break;
                }
                low = Math.min(low, lowest(from, to));
                if (low < height) {
                    break;
                }
                if (heights[to] == height || heights[to] < 0) {
                    best = next;
                }
            }
            if (best < 0 || weight(start, positions.get(best)) < minWeight) {
                return -1;
            }
            return best;
        }

        private int lowest(int start, int end) {
            int low = Integer.MAX_VALUE;
            for (int i = start; i < end; i++) {
                low = Math.min(low, lows[i]);
            }
            return low;
        }

        private void addBodies(int index, Deque<int[]> bodies) {
            var ins = instructions.get(index);
            if (!blockTypes.apply(ins).params().isEmpty()) {
                return;
            }
            int depth = ins.depth();
            int end = matchingEnd[index];
            int elseIndex = elseOf[index];
            if (ins.opcode() == OpCode.IF && elseIndex >= 0) {
                bodies.push(new int[] {elseIndex + 1, end, depth});
                bodies.push(new int[] {index + 1, elseIndex, depth});
            } else {
                bodies.push(new int[] {index + 1, end, depth});
            }
        }

        private boolean isPackable(int start, int end) {
            for (int i = start; i < end; i++) {
                var ins = instructions.get(i);
                switch (ins.opcode()) {
                    case RETURN_CALL:
                    case RETURN_CALL_INDIRECT:
                    case RETURN_CALL_REF:
                        return false;
                    case BR_TABLE:
                        if (ins.labelTable().size() > DISPATCH_TABLE_SIZE
                                && branchesOut(ins, start, end)) {
                            return false;
                        }
                        break;
                    default:
                        break;
                }
            }
            return true;
        }

        private static boolean branchesOut(AnnotatedInstruction ins, int start, int end) {
            for (int label : ins.labelTable()) {
                if (label <= start || label >= end) {
                    return true;
                }
            }
            return false;
        }

        private void flush(int start, int end, int depth) {
            BitSet read = new BitSet();
            BitSet written = new BitSet();
            for (int i = start; i < end; i++) {
                var ins = instructions.get(i);
                switch (ins.opcode()) {
                    case LOCAL_GET:
                        read.set((int) ins.operand(0));
                        break;
                    case LOCAL_SET:
                    case LOCAL_TEE:
                        written.set((int) ins.operand(0));
                        break;
                    default:
                        break;
                }
            }
            BitSet loaded = (BitSet) read.clone();
            loaded.or(written);
            boolean fallsThrough = heights[end] >= 0;
            segments.add(
                    new Segment(
                            segments.size(),
                            start,
                            end,
                            depth,
                            fallsThrough,
                            loaded.stream().toArray(),
                            written.stream().toArray()));
        }
    }
}
