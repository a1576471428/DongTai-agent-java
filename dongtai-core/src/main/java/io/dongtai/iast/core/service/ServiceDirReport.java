package io.dongtai.iast.core.service;

import io.dongtai.iast.common.constants.*;
import io.dongtai.iast.core.EngineManager;
import io.dongtai.log.DongTaiLog;
import org.json.JSONObject;

import java.io.File;

public class ServiceDirReport {

    private String serviceDir;
    private String serviceType;
    private StringBuilder dirStringBuilder = new StringBuilder();


    public ServiceDirReport() {
    }

    public String getServereAddressMsg() {
        if (new File("/var/run/secrets/kubernetes.io").exists()) {
            serviceType = "k8s";
        } else if (new File("/.dockerenv").exists()) {
            serviceType = "docker";
        } else {
            serviceType = "virtual";
        }
        JSONObject report = new JSONObject();
        JSONObject detail = new JSONObject();
        report.put(ReportKey.TYPE, ReportType.SERVICE_DIR);
        report.put(ReportKey.DETAIL, detail);

        detail.put(ReportKey.AGENT_ID, EngineManager.getAgentId());
        detail.put("serviceDir", this.serviceDir);
        detail.put("serviceType", this.serviceType);

        return report.toString();
    }

    public void send() {
        try {
            serviceDir = genDirTree(getWebServerPath(), 0, "");
            ThreadPools.sendReport(ApiPath.REPORT_UPLOAD, this.getServereAddressMsg());
        } catch (Exception e) {
            DongTaiLog.error("send ServiceDir to {} error, reason: {}", ApiPath.REPORT_UPLOAD, e);
        }
    }

    public String genDirTree(String path, int level, String dir) {
        // fixme: 防止目录过长导致启动卡死，后续对目录长度需讨论。
        if(dirStringBuilder.length()>65536){
            return dirStringBuilder.toString();
        }
        level++;
        File file = new File(path);
        File[] files = file.listFiles();
        if (!file.exists()) {
            return null;
        }
        if (files.length != 0) {
            for (File f : files) {
                if (f.isDirectory()) {
                    dir = f.getName();
                    genDirTree(f.getAbsolutePath(), level, dir);
                } else {
                    dirStringBuilder.append(f.getAbsolutePath()).append("\n");
                }
            }
        }
        return dirStringBuilder.toString();
    }

    public static String getWebServerPath() {
        File file = new File(".");
        String path = file.getAbsolutePath();
        return path.substring(0, path.length() - 2);
    }

}
