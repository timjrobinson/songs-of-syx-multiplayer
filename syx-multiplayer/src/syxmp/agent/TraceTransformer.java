package syxmp.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import syxmp.dev.VerbTrace;

/**
 * Dev-only (-Dsyxmp.trace=1): instruments candidate sim methods with
 * {@code if (VerbTrace.on) VerbTrace.enter(id);} so UI→sim mutation points
 * can be discovered empirically. Uses COMPUTE_MAXS only (no frame
 * recomputation needed for a straight-line prologue with one forward jump
 * — but the jump needs a frame on Java 7+, so we recompute frames too).
 */
public final class TraceTransformer implements ClassFileTransformer {

    private static final String TRACE = "syxmp/dev/VerbTrace";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> being,
            ProtectionDomain pd, byte[] bytes) {
        if (className == null || !VerbTrace.classCandidate(className))
            return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            // Straight-line prologue, no branches: existing stack map frames
            // stay valid, so no frame recomputation (which needs class
            // resolution and produced VerifyErrors).
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] touched = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private boolean isInterface;

                @Override
                public void visit(int version, int access, String name, String sig,
                        String superName, String[] interfaces) {
                    isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                    super.visit(version, access, name, sig, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (isInterface || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                            | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0)
                        return mv;
                    if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0)
                        return mv;
                    boolean zeroArgs = Type.getArgumentTypes(desc).length == 0;
                    boolean voidReturn = Type.getReturnType(desc).getSort() == Type.VOID;
                    if (!VerbTrace.methodCandidate(name, desc, zeroArgs, voidReturn))
                        return mv;
                    int id = VerbTrace.register(className.replace('/', '.') + "." + name + desc);
                    touched[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitLdcInsn(id);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "enter", "(I)V", false);
                        }
                    };
                }
            }, 0);
            return touched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            System.err.println("[SyxMP:trace] failed to instrument " + className + ": " + t);
            return null;
        }
    }
}
