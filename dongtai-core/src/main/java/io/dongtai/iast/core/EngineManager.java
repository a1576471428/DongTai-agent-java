package io.dongtai.iast.core;

import io.dongtai.iast.common.scope.ScopeManager;
import io.dongtai.iast.core.bytecode.enhance.plugin.fallback.FallbackManager;
import io.dongtai.iast.core.bytecode.enhance.plugin.fallback.FallbackSwitch;
import io.dongtai.iast.core.handler.context.ContextManager;
import io.dongtai.iast.core.handler.hookpoint.IastServer;
import io.dongtai.iast.core.handler.hookpoint.models.MethodEvent;
import io.dongtai.iast.core.handler.hookpoint.vulscan.taintrange.TaintRanges;
import io.dongtai.iast.core.service.ServerAddressReport;
import io.dongtai.iast.core.service.ServiceFactory;
import io.dongtai.iast.core.utils.PropertyUtils;
import io.dongtai.iast.core.utils.config.RemoteConfigUtils;
import io.dongtai.iast.core.utils.threadlocal.*;
import io.dongtai.log.DongTaiLog;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 存储全局信息
 *
 * @author dongzhiyong@huoxian.cn
 */
public class EngineManager {

    private static EngineManager instance;
    private final int agentId;
    private final boolean saveBytecode;

    public static final RequestContext REQUEST_CONTEXT = new RequestContext();
    public static final IastTrackMap TRACK_MAP = new IastTrackMap();
    public static final IastTaintHashCodes TAINT_HASH_CODES = new IastTaintHashCodes();
    public static final TaintRangesPool TAINT_RANGES_POOL = new TaintRangesPool();
    /**
     * 限制器统一管理器
     */
    private FallbackManager fallbackManager;

    public static IastServer SERVER;

    private static final AtomicInteger reqCounts = new AtomicInteger(0);
    public static int enableDongTai = 0;

    public static final BooleanThreadLocal ENTER_REPLAY_ENTRYPOINT = new BooleanThreadLocal(false);

    /**
     * hook点是否降级
     */
    public static boolean isHookPointFallback() {
        return FallbackSwitch.isRequestFallback();
    }

    /**
     * 打开hook点降级开关
     * 该开关打开后，在当前请求生命周期内，逻辑短路hook点
     */
    public static void openHookPointFallback(String className, String method, String methodSign, int hookType) {
        final double limitRate = EngineManager.getFallbackManager().getHookRateLimiter().getRate();
        DongTaiLog.debug("HookPoint rate limit! hookType: " + hookType + ", method:" + className + "." + method
                + ", sign:" + methodSign + " ,rate:" + limitRate);
//        HookPointRateLimitReport.sendReport(className, method, methodSign, hookType, limitRate);
        FallbackSwitch.setHeavyHookFallback(true);
    }

    public static EngineManager getInstance() {
        return instance;
    }

    public static EngineManager getInstance(int agentId) {
        if (instance == null) {
            instance = new EngineManager(agentId);
        }
        return instance;
    }

    public static void setInstance() {
        instance = null;
    }

    private EngineManager(int agentId) {
        PropertyUtils cfg = PropertyUtils.getInstance();
        this.saveBytecode = cfg.isEnableDumpClass();
        this.agentId = agentId;
        String fallbackVersion = System.getProperty("dongtai.fallback.version", "v2");
        if ("v2".equals(fallbackVersion)){
            RemoteConfigUtils.syncRemoteConfigV2(agentId);
        }else {
            RemoteConfigUtils.syncRemoteConfig(agentId);
        }
        this.fallbackManager = FallbackManager.newInstance(cfg.cfg);
    }

    public static FallbackManager getFallbackManager() {
        return instance.fallbackManager;
    }

    public static void setFallbackManager() {
        instance.fallbackManager = FallbackManager.updateInstance(PropertyUtils.getInstance().cfg);
    }

    /**
     * 清除当前线程的状态，避免线程重用导致的ThreadLocal产生内存泄漏的问题
     */
    public static void cleanThreadState() {
        EngineManager.REQUEST_CONTEXT.remove();
        EngineManager.TRACK_MAP.remove();
        EngineManager.TAINT_HASH_CODES.remove();
        EngineManager.TAINT_RANGES_POOL.remove();
        EngineManager.ENTER_REPLAY_ENTRYPOINT.remove();
        FallbackSwitch.clearHeavyHookFallback();
        EngineManager.getFallbackManager().getHookRateLimiter().remove();
        ContextManager.getCONTEXT().remove();
        ScopeManager.SCOPE_TRACKER.remove();
    }

    public static void maintainRequestCount() {
        EngineManager.reqCounts.getAndIncrement();
    }

    /**
     * 获取引擎已检测的请求数量
     *
     * @return 产生的请求数量
     */
    public static int getRequestCount() {
        return EngineManager.reqCounts.get();
    }

    /**
     * 打开检测引擎
     */
    public static void turnOnEngine() {
        EngineManager.enableDongTai = 1;
        FallbackSwitch.setPERFORMANCE_FALLBACK(false);
        FallbackSwitch.setHEAVY_TRAFFIC_LIMIT_FALLBACK(false);
    }

    /**
     * 关闭检测引擎
     */
    public static void turnOffEngine() {
        EngineManager.enableDongTai = 0;
    }

    /**
     * 检查灵芝引擎是否被开启
     *
     * @return true - 引擎已启动；false - 引擎未启动
     */
    public static boolean isEngineRunning() {
        return !FallbackSwitch.isEngineFallback() && EngineManager.enableDongTai == 1;
    }

    public boolean isEnableDumpClass() {
        return this.saveBytecode;
    }

    public static Integer getAgentId() {
        return instance.agentId;
    }

    public static void enterHttpEntry(Map<String, Object> requestMeta) {
        // 尝试获取请求限速令牌，耗尽时调用断路器方法
        if (!EngineManager.getFallbackManager().getHeavyTrafficRateLimiter().acquire()) {
            EngineManager.getFallbackManager().getHeavyTrafficBreaker().breakCheck(null);
        }

        ServiceFactory.startService();
        if (null == SERVER) {
            // todo: read server addr and send to OpenAPI Service
            String url = null;
            String protocol = null;
            if (null != requestMeta.get("serverName")){
                url = (String) requestMeta.get("serverName");
            }else {
                url = "";
            }
            if(null != requestMeta.get("protocol")){
                protocol = (String) requestMeta.get("protocol");
            }else {
                protocol = "";
            }

            SERVER = new IastServer(
                    url,
                    (Integer) requestMeta.get("serverPort"),
                    protocol,
                    true
            );
            ServerAddressReport serverAddressReport = new ServerAddressReport(EngineManager.SERVER.getServerAddr(),EngineManager.SERVER.getServerPort(),EngineManager.SERVER.getProtocol());
            serverAddressReport.run();
        }
        Map<String, String> headers = (Map<String, String>) requestMeta.get("headers");
        if (headers.containsKey("dt-traceid")) {
            ContextManager.getOrCreateGlobalTraceId(headers.get("dt-traceid"), EngineManager.getAgentId());
        } else {
            String newTraceId = ContextManager.getOrCreateGlobalTraceId(null, EngineManager.getAgentId());
            String spanId = ContextManager.getSpanId(newTraceId, EngineManager.getAgentId());
            headers.put("dt-traceid", newTraceId);
            headers.put("dt-spandid", spanId);
        }
        REQUEST_CONTEXT.set(requestMeta);
        TRACK_MAP.set(new HashMap<Integer, MethodEvent>(1024));
        TAINT_HASH_CODES.set(new HashSet<Integer>());
        TAINT_RANGES_POOL.set(new HashMap<Integer, TaintRanges>());
        ScopeManager.SCOPE_TRACKER.getHttpEntryScope().enter();
    }

    /**
     * @param dubboService
     * @param attachments
     * @since 1.2.0
     */
    public static void enterDubboEntry(String dubboService, Map<String, String> attachments) {
        // 尝试获取请求限速令牌，耗尽时调用断路器方法
        if (!EngineManager.getFallbackManager().getHeavyTrafficRateLimiter().acquire()) {
            EngineManager.getFallbackManager().getHeavyTrafficBreaker().breakCheck(null);
        }

        if (attachments != null) {
            if (attachments.containsKey(ContextManager.getHeaderKey())) {
                ContextManager.getOrCreateGlobalTraceId(attachments.get(ContextManager.getHeaderKey()),
                        EngineManager.getAgentId());
            } else {
                attachments.put(ContextManager.getHeaderKey(), ContextManager.getSegmentId());
            }
        }
        if (ScopeManager.SCOPE_TRACKER.getHttpEntryScope().in()) {
            return;
        }

        // todo: register server
        if (attachments != null) {
            Map<String, String> requestHeaders = new HashMap<String, String>(attachments.size());
            for (Map.Entry<String, String> entry : attachments.entrySet()) {
                requestHeaders.put(entry.getKey(), entry.getValue());
            }
            if (null == SERVER) {
                // todo: read server addr and send to OpenAPI Service
                SERVER = new IastServer(requestHeaders.get("dubbo"), 0, "TCP",true);
                String serverAddr = EngineManager.SERVER.getServerAddr();
                ServerAddressReport serverAddressReport = new ServerAddressReport(serverAddr,0, SERVER.getProtocol());
                serverAddressReport.run();
            }
            Map<String, Object> requestMeta = new HashMap<String, Object>(12);
            requestMeta.put("protocol", "dubbo/" + requestHeaders.get("dubbo"));
            requestMeta.put("scheme", "dubbo");
            requestMeta.put("method", "RPC");
            requestMeta.put("secure", "true");
            requestMeta.put("requestURL", dubboService.split("\\?")[0]);
            requestMeta.put("requestURI", requestHeaders.get("path"));
            requestMeta.put("remoteAddr", "");
            requestMeta.put("queryString", "");
            requestMeta.put("headers", requestHeaders);
            requestMeta.put("body", "");
            requestMeta.put("contextPath", "");
            requestMeta.put("replay-request", false);

            REQUEST_CONTEXT.set(requestMeta);
        }

        TRACK_MAP.set(new HashMap<Integer, MethodEvent>(1024));
        TAINT_HASH_CODES.set(new HashSet<Integer>());
    }

    public static boolean isEnterEntry(String currentFramework) {
        // @TODO: scope
        return false;
    }
}
