package io.dongtai.iast.core.utils;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class HashCode {
    public static int calc(Object obj) {
        if (obj instanceof String) {
            return ((String) obj).hashCode();
        } else {
            return System.identityHashCode(obj);
        }
    }
}
