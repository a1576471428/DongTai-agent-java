package io.dongtai.iast.core.handler.hookpoint.vulscan.normal;

import io.dongtai.iast.core.handler.hookpoint.models.MethodEvent;
import io.dongtai.iast.core.handler.hookpoint.models.policy.SinkNode;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class CryptoWeakRandomnessVulScan extends AbstractNormalVulScan {
    /**
     * 检查是否存在若随机数算法
     * fixme: 当出现如若随机数算法时，考虑如何列出出现若随机数算法的组件/平台/中间件，避免造成用户的困扰
     *
     * @param event    current method event
     * @param sinkNode current sink policy node
     */
    @Override
    public void scan(MethodEvent event, SinkNode sinkNode) {
        // todo: 取调用栈信息
        sendReport(getLatestStack(), sinkNode.getVulType());
    }
}
