package io.dongtai.iast.core.handler.hookpoint.controller.impl;

import io.dongtai.iast.core.EngineManager;
import io.dongtai.iast.core.handler.hookpoint.models.*;
import io.dongtai.iast.core.handler.hookpoint.vulscan.dynamic.TrackUtils;
import io.dongtai.iast.core.handler.hookpoint.vulscan.taintrange.*;
import io.dongtai.iast.core.utils.StackUtils;
import io.dongtai.iast.core.utils.TaintPoolUtils;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传播节点处理逻辑暂无问题，后续优先排查其他地方的问题
 *
 * @author dongzhiyong@huoxian.cn
 */
public class PropagatorImpl {
    private static final String PARAMS_OBJECT = "O";
    private static final String PARAMS_PARAM = "P";
    private static final String PARAMS_RETURN = "R";
    private static final String CONDITION_AND = "&";
    private static final String CONDITION_OR = "|";
    private static final String CONDITION_AND_RE_PATTERN = "[\\|&]";
    private static final int STACK_DEPTH = 11;

    public static void solvePropagator(MethodEvent event, AtomicInteger invokeIdSequencer) {
        if (!EngineManager.TAINT_POOL.isEmpty()) {
            IastPropagatorModel propagator = IastHookRuleModel.getPropagatorByMethodSignature(event.signature);
            if (propagator != null) {
                auxiliaryPropagator(propagator, invokeIdSequencer, event);
            } else {
                autoPropagator(invokeIdSequencer, event);
            }
        }
    }

    private static void addPropagator(MethodEvent event, AtomicInteger invokeIdSequencer) {
        event.source = false;
        event.setCallStacks(StackUtils.createCallStack(STACK_DEPTH));
        int invokeId = invokeIdSequencer.getAndIncrement();
        event.setInvokeId(invokeId);
        EngineManager.TRACK_MAP.get().put(invokeId, event);
    }

    private static void auxiliaryPropagator(IastPropagatorModel propagator, AtomicInteger invokeIdSequencer, MethodEvent event) {
        String sourceString = propagator.getSource();
        boolean conditionSource = contains(sourceString);
        if (!conditionSource) {
            if (PARAMS_OBJECT.equals(sourceString)) {
                if (!TaintPoolUtils.isNotEmpty(event.object)
                        || !TaintPoolUtils.isAllowTaintType(event.object)
                        || !TaintPoolUtils.poolContains(event.object, event)) {
                    return;
                }

                event.setInValue(event.object);
                setTarget(propagator, event);
                addPropagator(event, invokeIdSequencer);
            } else if (sourceString.startsWith(PARAMS_PARAM)) {
                ArrayList<Object> inValues = new ArrayList<Object>();
                int[] positions = (int[]) propagator.getSourcePosition();
                for (int pos : positions) {
                    if (pos >= event.argumentArray.length) {
                        continue;
                    }

                    Object tempObj = event.argumentArray[pos];
                    if (!TaintPoolUtils.isNotEmpty(tempObj)
                            || !TaintPoolUtils.isAllowTaintType(tempObj)
                            || !TaintPoolUtils.poolContains(tempObj, event)) {
                        continue;
                    }
                    inValues.add(tempObj);
                }
                if (!inValues.isEmpty()) {
                    event.setInValue(inValues.toArray());
                    setTarget(propagator, event);
                    addPropagator(event, invokeIdSequencer);
                }
            }
        } else {
            // o&r 解决
            // @TODO: R has been tainted, so we not need to propagate it
            boolean andCondition = sourceString.contains(CONDITION_AND);
            String[] conditionSources = sourceString.split(CONDITION_AND_RE_PATTERN);
            ArrayList<Object> inValues = new ArrayList<Object>();
            for (String source : conditionSources) {
                if (PARAMS_OBJECT.equals(source)) {
                    if (event.object == null) {
                        break;
                    }
                    inValues.add(event.object);
                } else if (PARAMS_RETURN.equals(source)) {
                    if (event.returnValue == null) {
                        break;
                    }
                    event.setInValue(event.returnValue);
                } else if (source.startsWith(PARAMS_PARAM)) {
                    int[] positions = (int[]) propagator.getSourcePosition();
                    for (int pos : positions) {
                        Object tempObj = event.argumentArray[pos];
                        if (tempObj != null) {
                            inValues.add(tempObj);
                        }
                    }
                }
            }
            if (!inValues.isEmpty()) {
                int condition = 0;
                for (Object obj : inValues) {
                    if (TaintPoolUtils.isNotEmpty(obj)
                            && TaintPoolUtils.isAllowTaintType(obj)
                            && TaintPoolUtils.poolContains(obj, event)) {
                        condition++;
                    }
                }
                if (condition > 0 && (!andCondition || conditionSources.length == condition)) {
                    event.setInValue(inValues.toArray());
                    setTarget(propagator, event);
                    addPropagator(event, invokeIdSequencer);
                }
            }
        }
    }

    private static void setTarget(IastPropagatorModel propagator, MethodEvent event) {
        String target = propagator.getTarget();
        if (PARAMS_OBJECT.equals(target)) {
            event.setOutValue(event.object);
            trackTaintRange(propagator, event);
        } else if (PARAMS_RETURN.equals(target)) {
            event.setOutValue(event.returnValue);
            trackTaintRange(propagator, event);
        } else if (target.startsWith(PARAMS_PARAM)) {
            ArrayList<Object> outValues = new ArrayList<Object>();
            Object tempPositions = propagator.getTargetPosition();
            int[] positions = (int[]) tempPositions;
            if (positions.length == 1) {
                event.setOutValue(event.argumentArray[positions[0]]);
                trackTaintRange(propagator, event);
            } else {
                for (int pos : positions) {
                    outValues.add(event.argumentArray[pos]);
                    trackTaintRange(propagator, event);
                }
                if (!outValues.isEmpty()) {
                    event.setOutValue(outValues.toArray());
                }
            }
        }
        if (TaintPoolUtils.isNotEmpty(event.outValue)) {
            EngineManager.TAINT_POOL.addTaintToPool(event.outValue, event, false);
        }
    }

    private static TaintRanges getTaintRanges(Object obj) {
        int hash = System.identityHashCode(obj);
        TaintRanges tr = EngineManager.TAINT_RANGES_POOL.get(hash);
        if (tr == null) {
            tr = new TaintRanges();
        } else {
            tr = tr.clone();
        }
        return tr;
    }

    private static void trackTaintRange(IastPropagatorModel propagator, MethodEvent event) {
        TaintCommandRunner r = TaintCommandRunner.getCommandRunner(event.signature);

        TaintRanges oldTaintRanges = new TaintRanges();
        TaintRanges srcTaintRanges = new TaintRanges();

        String srcValue = null;
        if (r != null) {
            String srcLoc = propagator.getSource();
            if (PARAMS_OBJECT.equals(srcLoc)) {
                srcTaintRanges = getTaintRanges(event.object);
                srcValue = TaintRangesBuilder.obj2String(event.object);
            } else if (srcLoc.startsWith("O|P")) {
                oldTaintRanges = getTaintRanges(event.object);
                int[] positions = (int[]) propagator.getSourcePosition();
                if (positions.length == 1 && event.argumentArray.length >= positions[0]) {
                    srcTaintRanges = getTaintRanges(event.argumentArray[positions[0]]);
                    srcValue = TaintRangesBuilder.obj2String(event.argumentArray[positions[0]]);
                }
            } else if (srcLoc.startsWith(PARAMS_PARAM)) {
                // invalid policy
                if (srcLoc.contains(CONDITION_OR) || srcLoc.contains(CONDITION_AND)) {
                    return;
                }
                int[] positions = (int[]) propagator.getSourcePosition();
                if (positions.length == 1 && event.argumentArray.length >= positions[0]) {
                    srcTaintRanges = getTaintRanges(event.argumentArray[positions[0]]);
                    srcValue = TaintRangesBuilder.obj2String(event.argumentArray[positions[0]]);
                }
            }
        }

        int tgtHash;
        String tgtValue;
        Object tgt;
        String tgtLoc = propagator.getTarget();
        if (PARAMS_OBJECT.equals(tgtLoc)) {
            tgt = event.object;
            tgtHash = System.identityHashCode(tgt);
            tgtValue = TaintRangesBuilder.obj2String(tgt);
            oldTaintRanges = getTaintRanges(tgt);
        } else if (PARAMS_RETURN.equals(tgtLoc)) {
            tgt = event.returnValue;
            tgtHash = System.identityHashCode(tgt);
            tgtValue = TaintRangesBuilder.obj2String(tgt);
        } else if (tgtLoc.startsWith(PARAMS_PARAM)) {
            // invalid policy
            if (tgtLoc.contains(CONDITION_OR) || tgtLoc.contains(CONDITION_AND)) {
                return;
            }
            int[] positions = (int[]) propagator.getTargetPosition();
            if (positions.length != 1 || event.argumentArray.length < positions[0]) {
                // target can only have one parameter
                return;
            }
            tgt = event.argumentArray[positions[0]];
            tgtHash = System.identityHashCode(tgt);
            tgtValue = TaintRangesBuilder.obj2String(tgt);
            oldTaintRanges = getTaintRanges(tgt);
        } else {
            // invalid policy
            return;
        }

        TaintRanges tr;
        if (r != null && srcValue != null) {
            tr = r.run(srcValue, tgtValue, event.argumentArray, oldTaintRanges, srcTaintRanges);
        } else {
            tr = new TaintRanges(new TaintRange(0, TaintRangesBuilder.getLength(tgt)));
        }
        event.targetRanges.add(new MethodEvent.MethodEventTargetRange(tgtHash, tgtValue, tr));
        EngineManager.TAINT_RANGES_POOL.add(tgtHash, tr);
    }

    private static void autoPropagator(AtomicInteger invokeIdSequence, MethodEvent event) {
        // 处理自动传播问题
        // 检查污点池，判断是否存在命中的污点
        Set<Object> pools = EngineManager.TAINT_POOL.get();
        for (Object taintValue : pools) {
            if (TrackUtils.smartEventMatchAndSetTaint(taintValue, event)) {
                // 将event.outValue加入污点池
                break;
                // 将当前方法加入污点方法池
            }
        }
        if (TaintPoolUtils.isNotEmpty(event.outValue)) {
            pools.add(event.outValue);
            if (event.outValue instanceof String) {
                event.addTargetHash(System.identityHashCode(event.outValue));
                event.addTargetHashForRpc(event.outValue.hashCode());
            } else {
                event.addTargetHash(event.outValue.hashCode());
            }
            addPropagator(event, invokeIdSequence);
        }
    }

    private static boolean contains(String obj) {
        return obj.contains(CONDITION_AND) || obj.contains(CONDITION_OR);
    }
}
