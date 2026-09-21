package android.util;

/** 仅限本地单测的日志出口，避免 Android SDK 桩遮蔽坏档恢复的真实结果。 */
public final class Log {
    private Log() { }

    /** 单测不依赖设备日志服务。 */
    public static int w(String tag, String message) { return 0; }

    /** 单测仍执行完整异常恢复流程。 */
    public static int w(String tag, String message, Throwable exception) { return 0; }

    /** 写入失败由返回值和保留的存储内容验证。 */
    public static int e(String tag, String message, Throwable exception) { return 0; }
}
