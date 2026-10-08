package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import java.io.IOException;

/** Core X11 FocusIn/FocusOut wire event (normal, nonlinear transition). */
public final class FocusNotify extends Event {
    private final Window window;
    public FocusNotify(Window window, boolean entering) { super(entering ? 9 : 10); this.window = window; }
    @Override public void send(short sequence, XOutputStream output) throws IOException {
        try (XStreamLock lock = output.lock()) {
            output.writeByte(code);
            output.writeByte((byte)3);
            output.writeShort(sequence);
            output.writeInt(window.id);
            output.writeByte((byte)0);
            output.writePad(23);
        }
    }
}
