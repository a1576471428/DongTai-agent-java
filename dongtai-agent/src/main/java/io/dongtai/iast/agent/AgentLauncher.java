package io.dongtai.iast.agent;

import io.dongtai.iast.agent.manager.EngineManager;
import io.dongtai.iast.agent.monitor.MonitorDaemonThread;
import io.dongtai.iast.agent.monitor.impl.EngineMonitor;
import io.dongtai.iast.agent.report.AgentRegisterReport;
import io.dongtai.iast.agent.util.ThreadUtils;
import io.dongtai.iast.common.constants.AgentConstant;
import io.dongtai.iast.common.scope.ScopeManager;
import io.dongtai.log.DongTaiLog;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class AgentLauncher {

    public static final String LAUNCH_MODE_AGENT = "agent";
    public static final String LAUNCH_MODE_ATTACH = "attach";
    public static String LAUNCH_MODE;
    private static Thread shutdownHook;

    static {
        /**
         * fix bug: agent use sun.net.http, then allowRestrictedHeaders is false, so some custom server has wrong
         */
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        /**
         * fix bug: java.lang.ClassCastException: weblogic.net.http.SOAPHttpsURLConnection cannot be cast to javax.net.ssl.HttpsURLConnection
         */
        System.setProperty("UseSunHttpHandler", "true");
    }

    /**
     * install agent with premain
     *
     * @param args boot args [namespace,token,ip,port,prop]
     * @param inst inst
     */
    public static void premain(String args, Instrumentation inst) {
        if (System.getProperty("protect.by.dongtai", null) != null) {
            return;
        }
        LAUNCH_MODE = LAUNCH_MODE_AGENT;
        try {
            IastProperties.getInstance();
            install(inst);
        } catch (Exception e) {
            System.out.println("[io.dongtai.iast.agent] agent premain failed: " + e.toString());
        }
    }

    /**
     * install agent with attach
     *
     * @param args boot args [namespace,token,ip,port,prop]
     * @param inst inst
     */
    public static void agentmain(String args, Instrumentation inst) {
        try {
            Map<String, String> argsMap = parseArgs(args);
            for (String prop : IastProperties.ATTACH_ARG_MAP.values()) {
                if (argsMap.containsKey(prop)) {
                    System.setProperty(prop, argsMap.get(prop));
                }
            }

            IastProperties.getInstance();
            System.out.println("[io.dongtai.iast.agent] Protect By DongTai IAST: " + System.getProperty("protect.by.dongtai", "false"));
            if ("uninstall".equals(argsMap.get("mode"))) {
                if (System.getProperty("protect.by.dongtai", null) == null) {
                    System.out.println("[io.dongtai.iast.agent] DongTai wasn't installed.");
                    return;
                }
                EngineMonitor.setIsUninstallHeart(true);
                DongTaiLog.info("Engine is about to be uninstalled");
                uninstall();
                // attach手动卸载后停止守护线程
                ThreadUtils.killAllDongTaiThreads();
                if (shutdownHook != null) {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    shutdownHook = null;
                }
                ScopeManager.SCOPE_TRACKER.remove();
                System.clearProperty("protect.by.dongtai");
            } else {
                if (System.getProperty("protect.by.dongtai", null) != null) {
                    DongTaiLog.info("DongTai already installed.");
                    return;
                }
                MonitorDaemonThread.isExit = false;
                LAUNCH_MODE = LAUNCH_MODE_ATTACH;
                install(inst);
            }
        } catch (Exception e) {
            System.out.println("[io.dongtai.iast.agent] agent agentmain failed: " + e.toString());
        }
    }


    /**
     * uninstall agent
     */
    @SuppressWarnings("unused")
    public static synchronized void uninstall() {
        EngineManager engineManager = EngineManager.getInstance();
        engineManager.uninstall();
    }

    /**
     * install agent
     *
     * @param inst inst
     */
    private static void install(final Instrumentation inst) {
        Boolean send = AgentRegisterReport.send();
        if (send) {
            DongTaiLog.init(AgentRegisterReport.getAgentFlag());
            LogCollector.extractFluent();
            DongTaiLog.info("Agent registered successfully.");
            Boolean agentStat = AgentRegisterReport.agentStat();
            if (!agentStat) {
                EngineMonitor.isCoreRegisterStart = false;
                DongTaiLog.info("Detection engine not started, agent waiting to be audited.");
            } else {
                EngineMonitor.isCoreRegisterStart = true;
            }
            shutdownHook = new ShutdownThread();
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            loadEngine(inst);
            System.setProperty("protect.by.dongtai", "true");
        } else {
            DongTaiLog.error("Agent registered failed. Start without DongTai IAST.");
        }
    }

    private static void loadEngine(final Instrumentation inst) {
        EngineManager engineManager = EngineManager.getInstance(inst, LAUNCH_MODE, EngineManager.getPID());
        MonitorDaemonThread daemonThread = new MonitorDaemonThread(engineManager);
        Thread agentMonitorDaemonThread = new Thread(daemonThread);
        if (MonitorDaemonThread.delayTime <= 0 && EngineMonitor.isCoreRegisterStart) {
            daemonThread.startEngine();
        }

        agentMonitorDaemonThread.setDaemon(true);
        agentMonitorDaemonThread.setPriority(1);
        agentMonitorDaemonThread.setName(AgentConstant.THREAD_NAME_PREFIX + "MonitorDaemon");
        agentMonitorDaemonThread.start();
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> argsMap = new HashMap<String, String>();
        String[] argsItems = args.split("&");
        for (String argsItem : argsItems) {
            String[] argItems = argsItem.split("=");
            argsMap.put(argItems[0], argItems[1]);
        }
        return argsMap;
    }
}
