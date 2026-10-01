/**
 * Capability-handling helpers for block entities.
 *
 * <ul>
 *   <li>{@link com.panzer.mods.celeris.framework.capability.CapabilityInvalidationFilter} --
 *       only invalidates capabilities on an actual structural state change,
 *       not on every state change.</li>
 *   <li>{@link com.panzer.mods.celeris.framework.capability.SingletonCapabilityRegistry} --
 *       one shared handler instance per owner type, for stateless or
 *       block-shared capability handlers.</li>
 * </ul>
 *
 * @since 0.1.0
 */
package com.panzer.mods.celeris.framework.capability;
