package io.dongtai.iast.core.bytecode.enhance.plugin.framework.grpc;

import io.dongtai.iast.core.bytecode.enhance.ClassContext;
import io.dongtai.iast.core.bytecode.enhance.plugin.AbstractClassVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

public class AbstractServerImplBuilderAdapter extends AbstractClassVisitor {
    public AbstractServerImplBuilderAdapter(ClassVisitor classVisitor, ClassContext context) {
        super(classVisitor, context);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (name.equals("addService") && descriptor.equals("(Lio/grpc/ServerServiceDefinition;)Lio/grpc/internal/AbstractServerImplBuilder;")) {
            mv = new AbstractServerImplBuilderAdviceAdapter(mv, access, name, descriptor);
            setTransformed();
        }
        return mv;
    }
}
