package com.robertsnest.aifactory.safety;

/** Operations the companion-mod protocol may describe across its lifecycle. */
public enum FactoryOperation {
    TELEMETRY_SNAPSHOT,
    SUBMIT_CRAFT,
    CANCEL_CRAFT,
    CONFIGURE_MACHINE,
    MOVE_INVENTORY,
    CONTROL_PRODUCTION,
    PLACE_BLUEPRINT
}
