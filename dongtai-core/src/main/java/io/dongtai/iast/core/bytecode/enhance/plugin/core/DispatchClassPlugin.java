package io.dongtai.iast.core.bytecode.enhance.plugin.core;

import io.dongtai.iast.core.bytecode.enhance.ClassContext;
import io.dongtai.iast.core.bytecode.enhance.MethodContext;
import io.dongtai.iast.core.bytecode.enhance.plugin.AbstractClassVisitor;
import io.dongtai.iast.core.bytecode.enhance.plugin.DispatchPlugin;
import io.dongtai.iast.core.bytecode.enhance.plugin.core.adapter.*;
import io.dongtai.iast.core.handler.hookpoint.models.policy.*;
import io.dongtai.iast.core.utils.AsmUtils;
import io.dongtai.log.DongTaiLog;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.JSRInlinerAdapter;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class DispatchClassPlugin implements DispatchPlugin {
    private Set<String> ancestors;
    private String className;

    public DispatchClassPlugin() {
    }

    @Override
    public ClassVisitor dispatch(ClassVisitor classVisitor, ClassContext classContext, Policy policy) {
        ancestors = classContext.getAncestors();
        className = classContext.getClassName();
        String matchedClassName = policy.getMatchedClass(className, ancestors);

        if (null == matchedClassName) {
            return classVisitor;
        }

        DongTaiLog.trace("class {} hit rule {}, class diagrams: {}", className, matchedClassName,
                Arrays.toString(ancestors.toArray()));
        classContext.setMatchedClassName(matchedClassName);
        return new ClassVisit(classVisitor, classContext, policy);
    }

    public class ClassVisit extends AbstractClassVisitor {
        private int classVersion;
        private final MethodAdapter[] methodAdapters;

        ClassVisit(ClassVisitor classVisitor, ClassContext classContext, Policy policy) {
            super(classVisitor, classContext, policy);
            this.methodAdapters = new MethodAdapter[]{
                    new SourceAdapter(),
                    new PropagatorAdapter(),
                    new SinkAdapter(),
            };
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (Modifier.isInterface(access) || Modifier.isAbstract(access) || "<clinit>".equals(name)) {
                return mv;
            }

            MethodContext methodContext = new MethodContext(this.context, name);
            methodContext.setModifier(access);
            methodContext.setDescriptor(descriptor);
            methodContext.setParameters(AsmUtils.buildParameterTypes(descriptor));

            String matchedSignature = AsmUtils.buildSignature(context.getMatchedClassName(), name, descriptor);

            mv = lazyAop(mv, access, name, descriptor, matchedSignature, methodContext);
            boolean methodIsTransformed = mv instanceof MethodAdviceAdapter;

            if (methodIsTransformed && this.classVersion < 50) {
                mv = new JSRInlinerAdapter(mv, access, name, descriptor, signature, exceptions);
            }

            if (methodIsTransformed) {
                DongTaiLog.trace("rewrite method {} for listener[class={}]", matchedSignature, context.getClassName());
            }

            return mv;
        }

        /**
         * 懒惰AOP，用于处理预定义HOOK点
         *
         * @param mv         方法访问器
         * @param access     方法访问控制符
         * @param name       方法名
         * @param descriptor 方法描述符
         * @param signature  方法签名
         * @return 修改后的方法访问器
         */
        private MethodVisitor lazyAop(MethodVisitor mv, int access, String name, String descriptor, String signature,
                                      MethodContext methodContext) {
            Set<PolicyNode> matchedNodes = new HashSet<PolicyNode>();

            List<SourceNode> sourceNodes = this.policy.getSources();
            if (sourceNodes != null && sourceNodes.size() != 0) {
                for (SourceNode sourceNode : sourceNodes) {
                    if (sourceNode.getMethodMatcher().match(methodContext)) {
                        matchedNodes.add(sourceNode);
                    }
                }
            }

            List<PropagatorNode> propagatorNodes = this.policy.getPropagators();
            if (sourceNodes != null && sourceNodes.size() != 0) {
                for (PropagatorNode propagatorNode : propagatorNodes) {
                    if (propagatorNode.getMethodMatcher().match(methodContext)) {
                        matchedNodes.add(propagatorNode);
                    }
                }
            }

            List<SinkNode> sinkNodes = this.policy.getSinks();
            if (sourceNodes != null && sourceNodes.size() != 0) {
                for (SinkNode sinkNode : sinkNodes) {
                    if (sinkNode.getMethodMatcher().match(methodContext)) {
                        matchedNodes.add(sinkNode);
                    }
                }
            }

            if (matchedNodes.size() > 0) {
                mv = new MethodAdviceAdapter(mv, access, name, descriptor, signature,
                        matchedNodes, methodContext, this.methodAdapters);
                setTransformed();
            }

            return mv;
        }
    }
}
