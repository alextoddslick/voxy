package me.cortex.voxy.client.core.util;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Drop-in replacement for JOML's {@code Matrix4f}/{@code VectorNf}/{@code VectorNi}
 * {@code getToAddress(long)} methods.
 * <p>
 * JOML's {@code getToAddress} is implemented via {@code org.joml.MemUtil$MemUtilUnsafe},
 * which uses {@code sun.misc.Unsafe} field-offset introspection and asserts (in a shared
 * static initializer, at class-init time) that every field of every supported type is laid
 * out in memory in exactly its declared order. That assumption is not guaranteed by the JVM
 * spec, and does not hold on JDK 21 on Apple Silicon: HotSpot lays out {@code Matrix4f.m00}
 * (and friends) in a different order than declared, so the very first JOML "unsafe" call in
 * the process throws {@code UnsupportedOperationException("Unexpected Matrix4f element
 * offset")} out of a static initializer - which then permanently poisons
 * {@code MemUtil$MemUtilUnsafe} for the rest of the process (every subsequent call fails
 * with {@code NoClassDefFoundError}), for every JOML type, not just the one that failed the
 * check. This is a pure JVM/library incompatibility, unrelated to the Zink/KosmicKrisp GL
 * backend, but Voxy is what actually calls {@code getToAddress} (vanilla/Sodium do not), so
 * it surfaces here. Running with {@code -Djoml.nounsafe=true} avoids the crash but makes
 * {@code getToAddress} throw unconditionally instead (JOML never implemented an NIO
 * fallback for it), so that is not viable either given how many call sites depend on it.
 * <p>
 * These helpers reimplement the exact same memory layout using JOML's public (safe, non-Unsafe)
 * getters/fields and LWJGL's own {@code MemoryUtil.memPutFloat/memPutInt}, which sidesteps the
 * broken code path entirely - correct on every platform, not just macOS.
 * <p>
 * {@code get(...)} methods below are the read-direction equivalent, replacing JOML's
 * {@code setFromAddress(long)} (same underlying broken Unsafe offset assumption).
 */
public class JomlAddressWriter {
    public static void put(Matrix4f m, long ptr) {
        MemoryUtil.memPutFloat(ptr, m.m00());
        MemoryUtil.memPutFloat(ptr + 4, m.m01());
        MemoryUtil.memPutFloat(ptr + 8, m.m02());
        MemoryUtil.memPutFloat(ptr + 12, m.m03());
        MemoryUtil.memPutFloat(ptr + 16, m.m10());
        MemoryUtil.memPutFloat(ptr + 20, m.m11());
        MemoryUtil.memPutFloat(ptr + 24, m.m12());
        MemoryUtil.memPutFloat(ptr + 28, m.m13());
        MemoryUtil.memPutFloat(ptr + 32, m.m20());
        MemoryUtil.memPutFloat(ptr + 36, m.m21());
        MemoryUtil.memPutFloat(ptr + 40, m.m22());
        MemoryUtil.memPutFloat(ptr + 44, m.m23());
        MemoryUtil.memPutFloat(ptr + 48, m.m30());
        MemoryUtil.memPutFloat(ptr + 52, m.m31());
        MemoryUtil.memPutFloat(ptr + 56, m.m32());
        MemoryUtil.memPutFloat(ptr + 60, m.m33());
    }

    public static void put(Vector4f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x);
        MemoryUtil.memPutFloat(ptr + 4, v.y);
        MemoryUtil.memPutFloat(ptr + 8, v.z);
        MemoryUtil.memPutFloat(ptr + 12, v.w);
    }

    public static void put(Vector3f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x);
        MemoryUtil.memPutFloat(ptr + 4, v.y);
        MemoryUtil.memPutFloat(ptr + 8, v.z);
    }

    public static void put(Vector3i v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x);
        MemoryUtil.memPutInt(ptr + 4, v.y);
        MemoryUtil.memPutInt(ptr + 8, v.z);
    }

    public static void put(Vector2f v, long ptr) {
        MemoryUtil.memPutFloat(ptr, v.x);
        MemoryUtil.memPutFloat(ptr + 4, v.y);
    }

    public static void put(Vector2i v, long ptr) {
        MemoryUtil.memPutInt(ptr, v.x);
        MemoryUtil.memPutInt(ptr + 4, v.y);
    }

    public static void get(Vector4f out, long ptr) {
        out.x = MemoryUtil.memGetFloat(ptr);
        out.y = MemoryUtil.memGetFloat(ptr + 4);
        out.z = MemoryUtil.memGetFloat(ptr + 8);
        out.w = MemoryUtil.memGetFloat(ptr + 12);
    }

    public static void get(Vector3f out, long ptr) {
        out.x = MemoryUtil.memGetFloat(ptr);
        out.y = MemoryUtil.memGetFloat(ptr + 4);
        out.z = MemoryUtil.memGetFloat(ptr + 8);
    }
}
