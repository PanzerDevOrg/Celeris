package com.panzer.mods.celeris_example;

import com.panzer.mods.celeris_example.compressor.CompressorSetup;
import com.panzer.mods.celeris_example.memorybank.MemoryBankSetup;
import com.panzer.mods.celeris_example.payloaddemo.PayloadDemoSetup;
import com.panzer.mods.celeris_example.pipe.PipeSetup;
import com.panzer.mods.celeris_example.registry.CelerisCreativeTabs;
import com.panzer.mods.celeris_example.ringbuffer.RingBufferDemoSetup;
import com.panzer.mods.celeris_example.wire.WireSetup;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the celeris_example mod — a separate, optional mod that
 * demonstrates Celeris's framework APIs via playable content. Not loaded
 * as part of the core Celeris mod.
 *
 * <p>Each block demonstrates one Celeris capability end-to-end:
 * <ul>
 *   <li>{@code compressor} -- async work via {@code AsyncResultQueue}</li>
 *   <li>{@code wire} -- {@code DiscreteGraph} + {@code EventDrivenPipelineSolver}</li>
 *   <li>{@code pipe} -- {@code SegmentedGraph} + {@code SIMDPipelineSolver}</li>
 *   <li>{@code memory_bank} -- {@code MemoryBus} off-heap state with real NBT persistence</li>
 *   <li>{@code payload_demo} -- {@code PacketPipeline} via {@code PayloadCompression}</li>
 *   <li>{@code ring_buffer_demo} -- {@code MpscRingBuffer} used directly</li>
 * </ul>
 */
@Mod(CelerisExample.MOD_ID)
public final class CelerisExample {

    public static final String MOD_ID = "celeris_example";
    private static final Logger LOGGER = LoggerFactory.getLogger(CelerisExample.class);

    public CelerisExample(IEventBus modEventBus, ModContainer ignorerdModContainer) {
        LOGGER.info("Celeris example content initializing");

        CompressorSetup.register(modEventBus);
        WireSetup.register(modEventBus);
        PipeSetup.register(modEventBus);
        MemoryBankSetup.register(modEventBus);
        PayloadDemoSetup.register(modEventBus);
        RingBufferDemoSetup.register(modEventBus);

        CelerisCreativeTabs.register(modEventBus);

        LOGGER.info("Celeris example content ready");
    }
}
