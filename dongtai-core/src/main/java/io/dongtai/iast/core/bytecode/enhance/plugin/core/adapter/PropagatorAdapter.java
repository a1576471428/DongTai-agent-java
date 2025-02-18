package io.dongtai.iast.core.bytecode.enhance.plugin.core.adapter;

import io.dongtai.iast.core.bytecode.enhance.MethodContext;
import io.dongtai.iast.core.handler.hookpoint.controller.impl.PropagatorImpl;
import io.dongtai.iast.core.handler.hookpoint.models.policy.PolicyNode;
import io.dongtai.iast.core.handler.hookpoint.models.policy.PropagatorNode;
import org.objectweb.asm.*;

import java.util.Set;

public class PropagatorAdapter extends MethodAdapter {
    @Override
    public void onMethodEnter(MethodAdviceAdapter adapter, MethodVisitor mv, MethodContext context,
                              Set<PolicyNode> policyNodes) {
        for (PolicyNode policyNode : policyNodes) {
            if (!(policyNode instanceof PropagatorNode)) {
                continue;
            }

            String signature = context.toString();
            enterScope(adapter, signature);
        }
    }

    @Override
    public void onMethodExit(MethodAdviceAdapter adapter, MethodVisitor mv, int opcode, MethodContext context,
                             Set<PolicyNode> policyNodes) {
        for (PolicyNode policyNode : policyNodes) {
            if (!(policyNode instanceof PropagatorNode)) {
                continue;
            }

            Label elseLabel = new Label();
            Label endLabel = new Label();

            String signature = context.toString();

            isFirstScope(adapter);
            mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);

            adapter.trackMethod(opcode, policyNode, true);

            adapter.mark(elseLabel);
            adapter.mark(endLabel);

            leaveScope(adapter, signature);
        }
    }

    private void enterScope(MethodAdviceAdapter adapter, String signature) {
        adapter.invokeStatic(ASM_TYPE_SPY_HANDLER, SPY_HANDLER$getDispatcher);
        if (PropagatorImpl.isSkipScope(signature)) {
            adapter.push(false);
        } else {
            adapter.push(true);
        }
        adapter.invokeInterface(ASM_TYPE_SPY_DISPATCHER, SPY$enterPropagator);
    }

    private void leaveScope(MethodAdviceAdapter adapter, String signature) {
        adapter.invokeStatic(ASM_TYPE_SPY_HANDLER, SPY_HANDLER$getDispatcher);
        if (PropagatorImpl.isSkipScope(signature)) {
            adapter.push(false);
        } else {
            adapter.push(true);
        }
        adapter.invokeInterface(ASM_TYPE_SPY_DISPATCHER, SPY$leavePropagator);
    }

    private void isFirstScope(MethodAdviceAdapter adapter) {
        adapter.invokeStatic(ASM_TYPE_SPY_HANDLER, SPY_HANDLER$getDispatcher);
        adapter.invokeInterface(ASM_TYPE_SPY_DISPATCHER, SPY$isFirstLevelPropagator);
    }
}
