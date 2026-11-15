package dev.usbmic.inspector.shell;

import android.content.Context;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Resource context for an unattached shell process; it never attaches as an Android system thread. */
public final class ShellContextBootstrap {
    private ShellContextBootstrap() { }
    public static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method isSystem = activityThread.getMethod("isSystem");
        requireNonSystem(isSystem);
        Constructor<?> constructor = activityThread.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        requireNonSystem(isSystem);
        if (context == null) throw new IllegalStateException("Resource context unavailable");
        return context;
    }
    private static void requireNonSystem(Method isSystem) throws Exception {
        if (!Boolean.FALSE.equals(isSystem.invoke(null))) throw new IllegalStateException("Non-system shell process required");
    }
}
