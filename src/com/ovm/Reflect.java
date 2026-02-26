package com.ovm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection utilities for accessing obfuscated Minecraft members.
 * All methods try multiple names (MCP deobfuscated + runtime obfuscated)
 * and silently fall back to defaults on failure.
 */
public class Reflect {

    /** Walk class hierarchy to find a declared field by name. */
    public static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    /** Walk class hierarchy to find a declared int field by name. */
    public static Field findIntField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() == int.class) { f.setAccessible(true); return f; }
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name + " (int)");
    }

    /**
     * Get a typed field value from obj, trying each name in order.
     * Tries public fields first, then hierarchy walk.
     * Returns type default (null/false/0) if not found.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getField(Object obj, Class<T> type, String... names) {
        for (String name : names) {
            try {
                Field f = obj.getClass().getField(name);
                return (T) fieldGet(f, obj, type);
            } catch (Exception ignored) {}
            try {
                Field f = findField(obj.getClass(), name);
                return (T) fieldGet(f, obj, type);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    /** Get a typed static field value, trying each name in order. */
    @SuppressWarnings("unchecked")
    public static <T> T getStaticField(Class<?> cls, Class<T> type, String... names) {
        for (String name : names) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(null);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    /** Invoke a no-arg method, trying each name. Returns type default on failure. */
    @SuppressWarnings("unchecked")
    public static <T> T invokeNoArg(Object obj, Class<T> type, String... names) {
        for (String name : names) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object result = m.invoke(obj);
                return result != null ? (T) result : defaultValue(type);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    /** Invoke a static no-arg method, trying each name. Returns type default on failure. */
    @SuppressWarnings("unchecked")
    public static <T> T invokeStatic(Class<?> cls, Class<T> type, String... names) {
        for (String name : names) {
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                Object result = m.invoke(null);
                return result != null ? (T) result : defaultValue(type);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    /** Invoke a method taking a single String arg, trying each name. */
    public static void invokeWithString(Object obj, String arg, String... names) {
        for (String name : names) {
            try { obj.getClass().getMethod(name, String.class).invoke(obj, arg); return; }
            catch (Exception ignored) {}
        }
    }

    /** Invoke a method taking a single float arg, trying each name. */
    public static void invokeWithFloat(Object obj, float arg, String... names) {
        for (String name : names) {
            try { obj.getClass().getMethod(name, float.class).invoke(obj, arg); return; }
            catch (Exception ignored) {}
        }
    }

    static Object fieldGet(Field f, Object obj, Class<?> type) throws Exception {
        if (type == boolean.class) return f.getBoolean(obj);
        if (type == int.class)     return f.getInt(obj);
        if (type == double.class)  return f.getDouble(obj);
        return f.get(obj);
    }

    @SuppressWarnings("unchecked")
    public static <T> T defaultValue(Class<T> type) {
        if (type == boolean.class) return (T) Boolean.FALSE;
        if (type == int.class)     return (T) Integer.valueOf(0);
        if (type == double.class)  return (T) Double.valueOf(0.0);
        return null;
    }
}
