package net.bichal.mods.celeris_example.pipe.net;

import net.bichal.mods.celeris_example.pipe.client.PipeClientState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers the one server-to-client payload of the pipe example. */
public final class PipeNetwork {

    private static final String VERSION = "1";

    private PipeNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(PipePayload.TYPE, PipePayload.STREAM_CODEC,
                (payload, context) -> PipeClientState.receive(payload));
    }
}
