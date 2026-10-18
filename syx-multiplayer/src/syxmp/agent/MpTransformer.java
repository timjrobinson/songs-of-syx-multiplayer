package syxmp.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Applies the PatchTable with ASM as classes load. This is the machinery
 * RW-MP gets from Harmony transpilers: entry hooks are the
 * "if (General(id, this, args)) return default;" prologue of
 * SyncTemplates.Transpiler; call redirects replace a virtual call with a
 * static one taking the receiver as the first argument.
 */
public final class MpTransformer implements ClassFileTransformer {

    private static final String TEMPLATES = "syxmp/agent/SyncTemplates";

    private final PatchTable table;

    public MpTransformer(PatchTable table) {
        this.table = table;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> being,
            ProtectionDomain pd, byte[] bytes) {
        if (className == null || !table.touches(className))
            return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
            reader.accept(new PatchingVisitor(writer, className), 0);
            System.out.println("[SyxMP] patched " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[SyxMP] failed to patch " + className + ": " + t);
            t.printStackTrace();
            return null;
        }
    }

    /** Resolves the common-superclass question without initializing classes. */
    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false,
                        ClassLoader.getSystemClassLoader());
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false,
                        ClassLoader.getSystemClassLoader());
                if (c1.isAssignableFrom(c2))
                    return type1;
                if (c2.isAssignableFrom(c1))
                    return type2;
                if (c1.isInterface() || c2.isInterface())
                    return "java/lang/Object";
                do {
                    c1 = c1.getSuperclass();
                } while (!c1.isAssignableFrom(c2));
                return c1.getName().replace('.', '/');
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    private final class PatchingVisitor extends ClassVisitor {
        private final String className;

        PatchingVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

            List<PatchTable.CallRedirect> redirects = new ArrayList<>();
            for (PatchTable.CallRedirect r : table.redirects)
                if (PatchTable.classMatches(r.inClass(), className)
                        && (r.inMethod() == null || r.inMethod().equals(name)))
                    redirects.add(r);
            if (!redirects.isEmpty())
                mv = new RedirectingMethodVisitor(mv, redirects);

            for (PatchTable.EntryHook h : table.hooks)
                if (h.owner().equals(className) && h.name().equals(name) && h.desc().equals(desc)) {
                    boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                    mv = new EntryHookMethodVisitor(mv, h.hookId(), isStatic, desc);
                }
            return mv;
        }
    }

    /** Rewrites matching virtual calls into static calls with the receiver prepended. */
    private static final class RedirectingMethodVisitor extends MethodVisitor {
        private final List<PatchTable.CallRedirect> redirects;

        RedirectingMethodVisitor(MethodVisitor mv, List<PatchTable.CallRedirect> redirects) {
            super(Opcodes.ASM9, mv);
            this.redirects = redirects;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            for (PatchTable.CallRedirect r : redirects) {
                if (!owner.equals(r.targetOwner()) || !desc.equals(r.targetDesc()))
                    continue;
                if (!(r.targetName().equals("*") || name.equals(r.targetName())))
                    continue;
                if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                    String newDesc = "(L" + r.targetOwner() + ";" + desc.substring(1);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, r.newOwner(), r.newName(),
                            newDesc, false);
                    return;
                }
                if (opcode == Opcodes.INVOKESTATIC) {
                    // static -> static: same descriptor, same name unless overridden
                    String newName = r.newName().equals("*") ? name : r.newName();
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, r.newOwner(), newName, desc, false);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    /** Injects the SyncTemplates.general guard at method entry. */
    private static final class EntryHookMethodVisitor extends MethodVisitor {
        private final int hookId;
        private final boolean isStatic;
        private final String desc;

        EntryHookMethodVisitor(MethodVisitor mv, int hookId, boolean isStatic, String desc) {
            super(Opcodes.ASM9, mv);
            this.hookId = hookId;
            this.isStatic = isStatic;
            this.desc = desc;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitLdcInsn(hookId);
            if (isStatic)
                mv.visitInsn(Opcodes.ACONST_NULL);
            else
                mv.visitVarInsn(Opcodes.ALOAD, 0);

            Type[] args = Type.getArgumentTypes(desc);
            pushInt(args.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            int local = isStatic ? 0 : 1;
            for (int i = 0; i < args.length; i++) {
                mv.visitInsn(Opcodes.DUP);
                pushInt(i);
                loadAndBox(args[i], local);
                mv.visitInsn(Opcodes.AASTORE);
                local += args[i].getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TEMPLATES, "general",
                    "(ILjava/lang/Object;[Ljava/lang/Object;)Z", false);
            Label cont = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, cont);
            emitDefaultReturn(Type.getReturnType(desc));
            mv.visitLabel(cont);
        }

        private void pushInt(int v) {
            if (v >= -1 && v <= 5)
                mv.visitInsn(Opcodes.ICONST_0 + v);
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)
                mv.visitIntInsn(Opcodes.BIPUSH, v);
            else
                mv.visitLdcInsn(v);
        }

        private void loadAndBox(Type t, int local) {
            switch (t.getSort()) {
                case Type.BOOLEAN -> {
                    mv.visitVarInsn(Opcodes.ILOAD, local);
                    box("java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
                }
                case Type.BYTE -> {
                    mv.visitVarInsn(Opcodes.ILOAD, local);
                    box("java/lang/Byte", "(B)Ljava/lang/Byte;");
                }
                case Type.CHAR -> {
                    mv.visitVarInsn(Opcodes.ILOAD, local);
                    box("java/lang/Character", "(C)Ljava/lang/Character;");
                }
                case Type.SHORT -> {
                    mv.visitVarInsn(Opcodes.ILOAD, local);
                    box("java/lang/Short", "(S)Ljava/lang/Short;");
                }
                case Type.INT -> {
                    mv.visitVarInsn(Opcodes.ILOAD, local);
                    box("java/lang/Integer", "(I)Ljava/lang/Integer;");
                }
                case Type.LONG -> {
                    mv.visitVarInsn(Opcodes.LLOAD, local);
                    box("java/lang/Long", "(J)Ljava/lang/Long;");
                }
                case Type.FLOAT -> {
                    mv.visitVarInsn(Opcodes.FLOAD, local);
                    box("java/lang/Float", "(F)Ljava/lang/Float;");
                }
                case Type.DOUBLE -> {
                    mv.visitVarInsn(Opcodes.DLOAD, local);
                    box("java/lang/Double", "(D)Ljava/lang/Double;");
                }
                default -> mv.visitVarInsn(Opcodes.ALOAD, local);
            }
        }

        private void box(String owner, String valueOfDesc) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", valueOfDesc, false);
        }

        private void emitDefaultReturn(Type ret) {
            switch (ret.getSort()) {
                case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case Type.LONG -> {
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                }
                case Type.FLOAT -> {
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                }
                case Type.DOUBLE -> {
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                }
                default -> {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                }
            }
        }
    }
}
