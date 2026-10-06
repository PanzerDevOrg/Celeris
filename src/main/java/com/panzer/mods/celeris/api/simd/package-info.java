/**
 * SIMD-or-scalar float-array math.
 *
 * <p>Start with {@link VectorOperations} --
 * a static facade that runs on real SIMD ({@code jdk.incubator.vector}) when
 * available and falls back to an equivalent scalar loop when it isn't. You
 * never choose the backend yourself; call the facade and it picks correctly
 * for the current JVM.
 *
 * <p>{@link VectorBackend} and its two
 * implementations are internal wiring, not something you should implement
 * or call directly -- they exist so {@code main} never has to name a
 * {@code jdk.incubator.vector} type at compile time (see the class-level
 * javadoc on {@code CelerisVectorRuntime} if you're curious why).
 *
 * @since 0.1.0
 */
package com.panzer.mods.celeris.api.simd;
