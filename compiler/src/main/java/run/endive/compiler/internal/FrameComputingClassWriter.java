package run.endive.compiler.internal;

import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} that computes frames and resolves the common super class of two types
 * through the compiler's own class loader. ASM's default resolves them through the loader that
 * loaded ASM, which cannot see runtime types such as {@code WasmException} when ASM comes from a
 * parent loader of the one holding the compiler and runtime.
 */
final class FrameComputingClassWriter extends ClassWriter {

    FrameComputingClassWriter() {
        super(COMPUTE_FRAMES);
    }

    @Override
    protected ClassLoader getClassLoader() {
        return FrameComputingClassWriter.class.getClassLoader();
    }
}
