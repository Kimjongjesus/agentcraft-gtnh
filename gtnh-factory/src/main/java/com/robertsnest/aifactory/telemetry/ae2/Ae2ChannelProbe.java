package com.robertsnest.aifactory.telemetry.ae2;

import appeng.api.implementations.IPowerChannelState;

/**
 * The only AE2 type reference in the GregTech reader path, kept in its own
 * class so a server without AE2 fails to link exactly this class and the
 * GregTech read continues with {@code meChannelActive = null}.
 */
public final class Ae2ChannelProbe {

    private Ae2ChannelProbe() {}

    /**
     * @return TRUE/FALSE for an AE2-backed part, null when the object does not
     *         expose a channel state
     */
    public static Boolean channelActive(Object hatch) {
        if (hatch instanceof IPowerChannelState) {
            IPowerChannelState state = (IPowerChannelState) hatch;
            // Powered AND active: a booting channel is not yet usable, and
            // reporting it as healthy would hide a real stall.
            return Boolean.valueOf(state.isPowered() && state.isActive());
        }
        return null;
    }
}
