package io.dongtai.iast.core.bytecode.enhance.plugin.spring;

import io.dongtai.iast.core.handler.hookpoint.IastClassLoader;
import io.dongtai.iast.core.handler.hookpoint.api.GetApiThread;
import io.dongtai.iast.core.handler.hookpoint.controller.impl.HttpImpl;
import io.dongtai.log.DongTaiLog;

import java.lang.reflect.Method;

/**
 * niuerzhuang@huoxian.cn
 */
public class SpringApplicationImpl {

    private static IastClassLoader iastClassLoader;
    public static Method getAPI;
    public static boolean isSend;

    public static void getWebApplicationContext(Object applicationContext) {
        if (!isSend && getClassLoader() != null) {
            loadApplicationContext();
            GetApiThread getApiThread = new GetApiThread(applicationContext);
            getApiThread.start();
        }
    }

    private static IastClassLoader getClassLoader() {
        iastClassLoader = HttpImpl.getClassLoader();
        return iastClassLoader;
    }

    private static void loadApplicationContext() {
        if (getAPI == null) {
            try {
                Class<?> proxyClass;
                proxyClass = iastClassLoader.loadClass("cn.huoxian.iast.spring.SpringApplicationContext");
                getAPI = proxyClass.getDeclaredMethod("getAPI", Object.class);
            } catch (NoSuchMethodException e) {
                DongTaiLog.error("SpringApplicationImpl.loadApplicationContext failed", e);
            } finally {
                iastClassLoader = null;
            }
        }
    }

}
