package dev.usbmic.inspector.shell;

/** Classifies asynchronous USB/framework enumeration without granting write authority. */
public final class UsbInventoryPolicy {
    public enum State { ABSENT, ATTACHED, SETTLING, REFUSED }
    private UsbInventoryPolicy() { }

    public static State classify(int physicalCount, boolean selectedDeviceMatches, boolean selectionProvided,
                                 int inputCount, int outputCount, boolean validPair) {
        if (physicalCount < 0 || inputCount < 0 || outputCount < 0
                || physicalCount > 1 || inputCount > 1 || outputCount > 1) return State.REFUSED;
        if (physicalCount == 0) {
            if (inputCount == 0 && outputCount == 0) return State.ABSENT;
            // The last framework port can outlive physical detach. It never permits a write.
            return selectionProvided ? State.SETTLING : State.REFUSED;
        }
        if (!selectionProvided || !selectedDeviceMatches) return State.REFUSED;
        // A chosen physical device often arrives before either AudioDeviceInfo endpoint.
        if (inputCount == 0 || outputCount == 0) return State.SETTLING;
        return validPair ? State.ATTACHED : State.REFUSED;
    }
}
