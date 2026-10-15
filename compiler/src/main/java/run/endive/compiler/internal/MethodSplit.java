package run.endive.compiler.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * A dispatch chain: blocks {@code B_n ... B_0} each opening the next, the innermost ending in
     * a wide {@code br_table}, and an arm of code after each block's end ({@code arm k} runs from
     * the end of {@code B_k} to the end of {@code B_k+1}). Compilers emit it for a large switch;
     * AssemblyScript's {@code __visit_members} is one with an arm per class. Its table alone can
     * pass the JIT's limit, so the arms move into arm segments and the root dispatches into them.
     */
    static final class Chain {
        /** The BLOCK opening the chain, B_n. */
        final int outer;

        /** The index of the br_table. */
        final int dispatch;

        /** {@code ends[k]} is the END of block B_k, the label arm k starts at; ends[n] closes it. */
        final int[] ends;

        /** The br_table entries, its default last. */
        final List<Integer> table;

        final Map<Integer, Integer> armOfLabel = new HashMap<>();

        /** The targets of the table outside the chain's arms. */
        final List<Integer> outside = new ArrayList<>();

        Segment[] segmentOfArm;
        int index;

        Chain(int outer, int dispatch, int[] ends, List<Integer> table) {
            this.outer = outer;
            this.dispatch = dispatch;
            this.ends = ends;
            this.table = table;
            for (int arm = 0; arm < ends.length - 1; arm++) {
                armOfLabel.put(ends[arm], arm);
            }
            for (int i = 0; i < table.size() - 1; i++) {
                int label = table.get(i);
                if (!armOfLabel.containsKey(label) && !outside.contains(label)) {
                    outside.add(label);
                }
            }
        }

        int arms() {
            return ends.length - 1;
        }

        /**
         * The arm of each table entry but the default, as the chars of a string the root loads as
         * a constant; a target outside the arms is {@code arms() + its index in outside}.
         */
        String armTable() {
            var chars = new StringBuilder();
            for (int i = 0; i < table.size() - 1; i++) {
                int label = table.get(i);
                Integer arm = armOfLabel.get(label);
                if (arm == null) {
                    arm = arms() + outside.indexOf(label);
                }
                chars.append((char) arm.intValue());
            }
            return chars.toString();
        }

        boolean isArmEnd(int index) {
            return armOfLabel.containsKey(index) && index != ends[0];
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

        /** For an arm segment: its chain and arms {@code [firstArm, endArm)}, else null. */
        Chain chain;

        int firstArm;
        int endArm;

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
            if (chain != null) {
                return chain.ends[firstArm] <= label && label < end;
            }
            return start < label && label < end;
        }

        boolean isArms() {
            return chain != null;
        }
    }

    private final List<Segment> segments;
    private final List<Chain> chains;
    private final Segment[] byStart;
    WasmAnalyzer.AnalysisResult rootAnalysis;

    /** Whether the function catches WASM exceptions, so segments must save locals unwinding. */
    boolean hasTryTable;

    private final int localCount;

    private MethodSplit(
            List<Segment> segments, List<Chain> chains, int instructionCount, int localCount) {
        this.segments = segments;
        this.chains = chains;
        this.byStart = new Segment[instructionCount];
        for (Segment segment : segments) {
            if (!segment.isArms()) {
                byStart[segment.start] = segment;
            }
        }
        this.localCount = localCount;
    }

    List<Segment> segments() {
        return segments;
    }

    Segment segmentAt(int index) {
        return byStart[index];
    }

    /** The chain whose br_table or first arm label is at {@code index}, or null. */
    Chain chainAt(int index) {
        for (Chain chain : chains) {
            if (chain.dispatch == index || chain.ends[0] == index) {
                return chain;
            }
        }
        return null;
    }

    List<Chain> chains() {
        return chains;
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
    // the arm table is a string constant: at most 65535 bytes of modified UTF-8, 3 per char
    private static final int MAX_CHAIN_ENTRIES = 65535 / 3;

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
            int chainEntries,
            Function<Instruction, FunctionType> blockTypes) {
        var planner = new Planner(body, profile, budget, minWeight, chainEntries, blockTypes);
        int last = body.instructions().size() - 1;
        planner.planBody(0, last, 0);
        if (planner.segments.isEmpty()) {
            return null;
        }
        int localCount = type.params().size() + body.localTypes().size();
        return new MethodSplit(
                planner.segments, planner.chains, body.instructions().size(), localCount);
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
        private final int chainEntries;
        private final Function<Instruction, FunctionType> blockTypes;
        private final List<Chain> chains = new ArrayList<>();
        private final int[] matchingEnd;
        private final int[] elseOf;
        private final long[] weightBefore;
        private final List<Segment> segments = new ArrayList<>();

        Planner(
                FunctionBody body,
                WasmAnalyzer.StackProfile profile,
                int budget,
                int minWeight,
                int chainEntries,
                Function<Instruction, FunctionType> blockTypes) {
            this.instructions = body.instructions();
            this.chainEntries = chainEntries;
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
                if (opensConstruct(element) && !planChain(element)) {
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

        /** Splits the dispatch chain opened at {@code index} into arm segments, if it is one. */
        private boolean planChain(int index) {
            var chain = chainAt(index);
            if (chain == null) {
                return false;
            }
            int arms = chain.arms();
            List<int[]> groups = new ArrayList<>();
            int first = 0;
            while (first < arms) {
                int end = first;
                while (end < arms && weight(chain.ends[first] + 1, chain.ends[end + 1]) <= budget) {
                    end++;
                }
                if (end == first) {
                    return false;
                }
                groups.add(new int[] {first, end});
                first = end;
            }
            for (int i = chain.ends[0] + 1; i < chain.ends[arms]; i++) {
                switch (instructions.get(i).opcode()) {
                    case RETURN_CALL:
                    case RETURN_CALL_INDIRECT:
                    case RETURN_CALL_REF:
                        return false;
                    default:
                        break;
                }
            }

            chain.segmentOfArm = new Segment[arms];
            for (int[] group : groups) {
                int start = chain.ends[group[0]] + 1;
                int end = chain.ends[group[1]];
                var segment = segment(start, end, 0, false);
                segment.chain = chain;
                segment.firstArm = group[0];
                segment.endArm = group[1];
                for (int arm = group[0]; arm < group[1]; arm++) {
                    chain.segmentOfArm[arm] = segment;
                }
                segments.add(segment);
            }
            chain.index = chains.size();
            chains.add(chain);
            return true;
        }

        private Chain chainAt(int index) {
            List<Integer> blocks = new ArrayList<>();
            int at = index;
            while (instructions.get(at).opcode() == OpCode.BLOCK) {
                var type = blockTypes.apply(instructions.get(at));
                if (!type.params().isEmpty() || !type.returns().isEmpty()) {
                    return null;
                }
                blocks.add(at);
                at++;
            }
            if (blocks.size() < 2) {
                return null;
            }
            int innermost = blocks.get(blocks.size() - 1);
            int dispatch = matchingEnd[innermost] - 1;
            var ins = instructions.get(dispatch);
            if (ins.opcode() != OpCode.BR_TABLE
                    || ins.labelTable().size() <= Math.max(DISPATCH_TABLE_SIZE, chainEntries)
                    || ins.labelTable().size() > MAX_CHAIN_ENTRIES
                    || heights[dispatch] != heights[index] + 1
                    || heights[index] < 0) {
                return null;
            }
            for (int label : ins.labelTable()) {
                if (carriesValues(label, dispatch)) {
                    return null;
                }
            }
            int[] ends = new int[blocks.size()];
            for (int k = 0; k < blocks.size(); k++) {
                ends[k] = matchingEnd[blocks.get(blocks.size() - 1 - k)];
            }
            return new Chain(index, dispatch, ends, ins.labelTable());
        }

        /** Whether a branch from {@code from} to {@code label} carries values. */
        private boolean carriesValues(int label, int from) {
            if (label <= from) {
                var loop = instructions.get(label - 1);
                return !blockTypes.apply(loop).params().isEmpty();
            }
            var scope = instructions.get(label).scope();
            if (scope.opcode() == OpCode.END) {
                return true;
            }
            return !blockTypes.apply(scope).returns().isEmpty();
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
            segments.add(segment(start, end, depth, heights[end] >= 0));
        }

        private Segment segment(int start, int end, int depth, boolean fallsThrough) {
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
            return new Segment(
                    segments.size(),
                    start,
                    end,
                    depth,
                    fallsThrough,
                    loaded.stream().toArray(),
                    written.stream().toArray());
        }
    }
}
