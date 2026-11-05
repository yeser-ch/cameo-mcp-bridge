package com.claude.cameo.bridge.util;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.properties.PropertyManager;
import com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement;
import com.nomagic.magicdraw.uml.symbols.PresentationElement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Bridges presentation APIs that changed between Cameo 2024x and CATIA Magic 2026x.
 * <p>
 * 2024x exposes {@code PresentationElement.getPropertyManager()},
 * {@code DiagramPresentationElement.layout(boolean)},
 * {@code isDiagramWindowOpen()} and {@code isDiagramWindowActive()} directly.
 * These were removed or replaced in 2026x, so they are resolved via reflection to
 * keep the plugin compiling against both releases.
 */
public final class CameoApiCompat {

    private CameoApiCompat() {
    }

    /** Replacement for {@code pe.getPropertyManager()}. */
    public static PropertyManager getPropertyManager(PresentationElement pe) {
        Object direct = invokeIfPresent(pe, "getPropertyManager");
        if (direct instanceof PropertyManager) {
            return (PropertyManager) direct;
        }
        // 2026x: look for any public no-arg accessor that yields a PropertyManager.
        Method best = null;
        for (Method m : pe.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                    || !PropertyManager.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            if (best == null || (m.getName().contains("Property") && !best.getName().contains("Property"))) {
                best = m;
            }
        }
        if (best != null) {
            Object value = invoke(pe, best);
            if (value instanceof PropertyManager) {
                return (PropertyManager) value;
            }
        }
        throw new UnsupportedOperationException(
                "No PropertyManager accessor found on " + pe.getClass().getName());
    }

    /** Replacement for {@code dpe.layout(true)}. Returns whether a layout was applied. */
    public static boolean layout(DiagramPresentationElement dpe) {
        Method legacy = findMethod(dpe.getClass(), "layout", boolean.class);
        if (legacy != null) {
            return toBoolean(invoke(dpe, legacy, Boolean.TRUE));
        }
        Object layouter = invokeIfPresent(dpe, "getDefaultDiagramLayouter");
        if (layouter != null) {
            for (Method m : dpe.getClass().getMethods()) {
                if (!m.getName().equals("layout")) {
                    continue;
                }
                Object[] args = buildArgs(m.getParameterTypes(), layouter, dpe);
                if (args != null) {
                    return toBoolean(invoke(dpe, m, args));
                }
            }
            for (Method m : layouter.getClass().getMethods()) {
                if (!m.getName().equals("layout") && !m.getName().equals("apply")) {
                    continue;
                }
                Object[] args = buildArgs(m.getParameterTypes(), layouter, dpe);
                if (args != null) {
                    return toBoolean(invoke(layouter, m, args));
                }
            }
        }
        throw new UnsupportedOperationException(
                "No diagram layout API found on " + dpe.getClass().getName());
    }

    /** Replacement for {@code dpe.isDiagramWindowOpen()}. */
    public static boolean isDiagramWindowOpen(DiagramPresentationElement dpe) {
        Method legacy = findMethod(dpe.getClass(), "isDiagramWindowOpen");
        if (legacy != null) {
            return toBoolean(invoke(dpe, legacy));
        }
        if (findMethod(dpe.getClass(), "getDiagramSurface") != null) {
            return invokeIfPresent(dpe, "getDiagramSurface") != null;
        }
        return isDiagramWindowActive(dpe);
    }

    /** Replacement for {@code dpe.isDiagramWindowActive()}. */
    public static boolean isDiagramWindowActive(DiagramPresentationElement dpe) {
        Method legacy = findMethod(dpe.getClass(), "isDiagramWindowActive");
        if (legacy != null) {
            return toBoolean(invoke(dpe, legacy));
        }
        Project project = Application.getInstance().getProject();
        return project != null && invokeIfPresent(project, "getActiveDiagram") == dpe;
    }

    private static Object[] buildArgs(Class<?>[] types, Object layouter, Object dpe) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == boolean.class || t == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (t.isInstance(layouter)) {
                args[i] = layouter;
            } else if (t.isInstance(dpe)) {
                args[i] = dpe;
            } else {
                return null;
            }
        }
        return args;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeIfPresent(Object target, String name) {
        Method m = findMethod(target.getClass(), name);
        return m != null ? invoke(target, m) : null;
    }

    private static Object invoke(Object target, Method m, Object... args) {
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(m.getName() + " failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access " + m, e);
        }
    }

    private static boolean toBoolean(Object value) {
        return !(value instanceof Boolean) || (Boolean) value;
    }
}
